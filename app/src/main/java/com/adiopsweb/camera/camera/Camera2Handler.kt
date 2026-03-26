package com.adiopsweb.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*

private const val TAG = "Camera2Handler"

class Camera2Handler(
    private val context: Context,
    private val textureView: TextureView,
    private val onError: (String) -> Unit,
    private val onPhotoSaved: (String) -> Unit,
    private val onVideoStateChanged: (Boolean) -> Unit
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentLens = LensMode.WIDE
    private var captureMode = CaptureMode.PHOTO
    private var videoResolution = VideoResolution.UHD_4K
    private var frameRate = FrameRate.FPS_30
    private var proSettings = ProSettings()
    private var colorProfile = ColorProfile.NATURAL
    private var isRecording = false
    private var isFlashOn = false
    private var showGrid = false
    private var isTimerActive = false
    private var timerSeconds = 0
    private var isHdrEnabled = false
    private var isRawEnabled = false

    // Pinch-to-zoom digital zoom ratio (1.0 = no zoom)
    private var digitalZoom = 1.0f

    /** Start background thread for camera operations */
    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /** Stop background thread */
    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread interrupt", e)
        }
    }

    /** Get the main back-facing camera ID */
    private fun getBackCameraId(): String {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"
    }

    /** Open the camera (always uses the logical back camera; lens is set via zoom ratio) */
    @SuppressLint("MissingPermission")
    fun openCamera(lens: LensMode = currentLens) {
        currentLens = lens
        closeCamera()
        val cameraId = getBackCameraId()
        Log.d(TAG, "Opening camera $cameraId")

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    onError("Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError("Cannot access camera: ${e.message}")
        }
    }

    // Actual buffer dimensions chosen for preview (landscape, camera-native)
    private var bufW = 1920
    private var bufH = 1080

    /**
     * Pick the best-fit landscape preview size from what the camera supports.
     * Targets the screen's own aspect ratio (rotated to landscape) so the crop
     * is minimal on the OnePlus 13's 20:9 panel (1440×3168 or 1080×2376).
     */
    private fun chooseBestPreviewSize(cameraId: String): Pair<Int, Int> {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Pair(1920, 1080)

        val screenW = textureView.width.coerceAtLeast(1)
        val screenH = textureView.height.coerceAtLeast(1)
        // In portrait the long side is the height; as a landscape ratio that's h:w
        val targetRatio = screenH.toFloat() / screenW.toFloat()

        val best = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width >= 1280 }
            ?.minByOrNull { s ->
                val r = s.width.toFloat() / s.height.toFloat()
                kotlin.math.abs(r - targetRatio)
            }
        return if (best != null) Pair(best.width, best.height) else Pair(1920, 1080)
    }

    /**
     * Rotate & scale the TextureView so the landscape camera buffer fills
     * the portrait screen without stretching (centre-crop, no black bars).
     * Works for any screen resolution including OnePlus 13's 1440×3168.
     */
    private fun applyPreviewTransform() {
        val vW = textureView.width.toFloat()
        val vH = textureView.height.toFloat()
        if (vW == 0f || vH == 0f) return

        val chars = cameraManager.getCameraCharacteristics(getBackCameraId())
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val matrix = android.graphics.Matrix()
        val cx = vW / 2f
        val cy = vH / 2f

        // Rotate landscape buffer to portrait orientation
        matrix.postRotate(sensorOrientation.toFloat(), cx, cy)

        // After 90°/270° rotation bufW↔bufH swap: effective portrait = bufH × bufW
        val effW = if (sensorOrientation % 180 == 90) bufH.toFloat() else bufW.toFloat()
        val effH = if (sensorOrientation % 180 == 90) bufW.toFloat() else bufH.toFloat()

        // Centre-crop scale: fill the screen, clip the smaller dimension
        val scale = maxOf(vW / effW, vH / effH)
        matrix.postScale(scale, scale, cx, cy)

        textureView.setTransform(matrix)
    }

    /** Create preview capture session */
    @Suppress("DEPRECATION")
    private fun createPreviewSession() {
        val texture = textureView.surfaceTexture ?: return

        // Pick the best landscape preview size for this screen's aspect ratio
        val (w, h) = chooseBestPreviewSize(getBackCameraId())
        bufW = w; bufH = h
        texture.setDefaultBufferSize(bufW, bufH)
        val previewSurface = Surface(texture)
        // Apply transform on UI thread
        android.os.Handler(android.os.Looper.getMainLooper()).post { applyPreviewTransform() }

        // ImageReader for still capture
        imageReader = ImageReader.newInstance(4000, 3000,
            if (isRawEnabled) ImageFormat.RAW_SENSOR else ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            savePhoto(image)
            image.close()
        }, backgroundHandler)

        val surfaces = mutableListOf(previewSurface, imageReader!!.surface)

        try {
            cameraDevice?.createCaptureSession(surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview(previewSurface)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError("Session configuration failed")
                    }
                }, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError("Session creation failed: ${e.message}")
        }
    }

    /** Start the repeating preview request */
    private fun startPreview(previewSurface: Surface) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return

        val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            applyProSettings(this)
        }

        try {
            session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError("Preview failed: ${e.message}")
        }
    }

    /** Apply manual / pro settings to a capture request builder */
    private fun applyProSettings(builder: CaptureRequest.Builder) {
        // Auto or manual exposure
        if (proSettings.iso == 0 && proSettings.shutterUs == 0L) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                proSettings.exposureCompensation)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            if (proSettings.iso > 0) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, proSettings.iso)
            }
            if (proSettings.shutterUs > 0L) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                    proSettings.shutterUs * 1000L) // convert µs -> ns
            }
        }

        // White balance
        if (proSettings.whiteBalance == WhiteBalance.AUTO) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, proSettings.whiteBalance.awbMode)
        }

        // Focus
        when (proSettings.focusMode) {
            FocusMode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
            FocusMode.CONTINUOUS -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            FocusMode.MANUAL -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE,
                    proSettings.manualFocusDistance * 10f)
            }
        }

        // Flash
        builder.set(CaptureRequest.FLASH_MODE,
            if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH
            else CaptureRequest.FLASH_MODE_OFF)

        // Lens selection + digital zoom via CONTROL_ZOOM_RATIO (API 30+) or crop region
        applyZoom(builder)
    }

    /** Apply lens zoom using CONTROL_ZOOM_RATIO (API 30+) or SCALER_CROP_REGION fallback */
    private fun applyZoom(builder: CaptureRequest.Builder) {
        val totalZoom = currentLens.zoomFactor * maxOf(digitalZoom, 1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: zoom ratio < 1 = ultrawide, 1 = wide, 3 = tele
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, totalZoom)
        } else {
            // API 26-29: crop region for zoom > 1; ultrawide not reachable via crop
            if (totalZoom > 1.0f) applyDigitalZoom(builder, totalZoom)
        }
    }

    /** Crop-region digital zoom fallback for API < 30 */
    private fun applyDigitalZoom(builder: CaptureRequest.Builder, zoom: Float) {
        val chars = cameraManager.getCameraCharacteristics(getBackCameraId())
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val cropW = (sensorRect.width() / zoom).toInt()
        val cropH = (sensorRect.height() / zoom).toInt()
        val cropX = (sensorRect.width() - cropW) / 2
        val cropY = (sensorRect.height() - cropH) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
    }

    /** Trigger autofocus tap-to-focus at the given normalized coordinates */
    fun triggerTapToFocus(normX: Float, normY: Float) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val cameraId = getBackCameraId()
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val focusSize = 200
        val x = (normX * sensorRect.width()).toInt().coerceIn(focusSize, sensorRect.width() - focusSize)
        val y = (normY * sensorRect.height()).toInt().coerceIn(focusSize, sensorRect.height() - focusSize)
        val focusRect = MeteringRectangle(
            x - focusSize, y - focusSize, focusSize * 2, focusSize * 2, 1000
        )

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            val texture = textureView.surfaceTexture ?: return
            addTarget(Surface(texture))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            applyProSettings(this)
        }
        try {
            session.capture(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Tap-to-focus failed", e)
        }
    }

    /** Capture a still photo */
    fun capturePhoto() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.JPEG_QUALITY, 95)
            applyProSettings(this)
            if (isHdrEnabled) {
                set(CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_HDR)
            }
        }

        try {
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Photo captured")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError("Capture failed: ${e.message}")
        }
    }

    private fun savePhoto(image: android.media.Image) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "PROCAM_$timestamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ProCam13")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { stream ->
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                stream.write(bytes)
            }
            onPhotoSaved(filename)
        }
    }

    /** Start video recording */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun startVideoRecording() {
        if (isRecording) return
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        // Cap at 1080p for Camera2 API compatibility; 4K requires vendor-specific setup
        val safeRes = if (videoResolution == VideoResolution.UHD_4K) VideoResolution.FHD_1080P
                      else videoResolution
        // Clamp fps: 120/240 slow-mo needs separate high-speed session; cap at 60 for standard
        val safeFps = frameRate.fps.coerceAtMost(60)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputValues = ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "VID_$timestamp.mp4")
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProCam13")
        }
        val videoUri = context.contentResolver.insert(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, outputValues
        ) ?: run { onError("Cannot create video file"); return }

        val videoFd = context.contentResolver.openFileDescriptor(videoUri, "w")
            ?: run { onError("Cannot open video file"); return }

        @Suppress("DEPRECATION")
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(safeRes.width, safeRes.height)
            setVideoFrameRate(safeFps)
            val bitrate = when (safeRes) {
                VideoResolution.UHD_4K    -> 50_000_000
                VideoResolution.FHD_1080P -> 20_000_000
                VideoResolution.HD_720P   -> 8_000_000
            }
            setVideoEncodingBitRate(bitrate)
            setOutputFile(videoFd.fileDescriptor)
            prepare()
        }

        val recorderSurface = mediaRecorder!!.surface
        // Preview buffer: landscape size matching video resolution
        texture.setDefaultBufferSize(safeRes.width, safeRes.height)
        val previewSurface = Surface(texture)

        try {
            camera.createCaptureSession(listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(previewSurface)
                            addTarget(recorderSurface)
                            // Use a flexible FPS range so AE can adapt
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(24, safeFps))
                            applyProSettings(this)
                        }
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        mediaRecorder?.start()
                        isRecording = true
                        videoFd.close()
                        onVideoStateChanged(true)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        videoFd.close()
                        onError("Video session config failed")
                    }
                }, backgroundHandler)
        } catch (e: Exception) {
            videoFd.close()
            onError("Start recording failed: ${e.message}")
        }
    }

    /** Stop video recording */
    fun stopVideoRecording() {
        if (!isRecording) return
        try {
            captureSession?.stopRepeating()
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            mediaRecorder = null
            isRecording = false
            onVideoStateChanged(false)
            openCamera(currentLens) // Re-open for preview
        } catch (e: Exception) {
            onError("Stop recording failed: ${e.message}")
        }
    }

    /** Close the current camera device */
    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    // Setters for UI controls
    // Lens switch: update zoom ratio in the ongoing session — no camera reopen needed
    fun setLens(lens: LensMode) { currentLens = lens; restartPreview() }
    fun setCaptureMode(mode: CaptureMode) { captureMode = mode }
    fun setVideoResolution(res: VideoResolution) { videoResolution = res }
    fun setFrameRate(fps: FrameRate) { frameRate = fps }
    fun setProSettings(settings: ProSettings) {
        proSettings = settings
        restartPreview()
    }
    fun setColorProfile(profile: ColorProfile) { colorProfile = profile }
    fun setFlash(on: Boolean) { isFlashOn = on; restartPreview() }
    fun setHdr(on: Boolean) { isHdrEnabled = on }
    fun setRaw(on: Boolean) { isRawEnabled = on }
    fun setDigitalZoom(zoom: Float) { digitalZoom = zoom.coerceIn(1.0f, 10.0f); restartPreview() }
    fun isCurrentlyRecording() = isRecording
    fun getCurrentLens() = currentLens

    private fun restartPreview() {
        val texture = textureView.surfaceTexture ?: return
        val previewSurface = Surface(texture)
        startPreview(previewSurface)
    }
}
