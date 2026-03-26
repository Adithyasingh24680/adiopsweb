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

    /** Map LensMode to the correct physical camera ID on OnePlus 13 */
    private fun getCameraIdForLens(lens: LensMode): String {
        val ids = cameraManager.cameraIdList
        for (id in ids) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (focalLengths.isNullOrEmpty()) continue

            val fl = focalLengths[0]
            when (lens) {
                LensMode.ULTRAWIDE -> if (fl < 2.0f) return id
                LensMode.WIDE      -> if (fl in 3.5f..5.5f) return id
                LensMode.TELEPHOTO -> if (fl > 6.0f) return id
            }
        }
        // Fallback: return the first back-facing camera
        return ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"
    }

    /** Open the camera for the given lens */
    @SuppressLint("MissingPermission")
    fun openCamera(lens: LensMode = currentLens) {
        currentLens = lens
        closeCamera()
        val cameraId = getCameraIdForLens(lens)
        Log.d(TAG, "Opening camera $cameraId for lens $lens")

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

    /** Create preview capture session */
    private fun createPreviewSession() {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(textureView.width, textureView.height)
        val previewSurface = Surface(texture)

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

        // Digital zoom via crop region
        applyDigitalZoom(builder)
    }

    /** Apply digital zoom by cropping sensor array */
    private fun applyDigitalZoom(builder: CaptureRequest.Builder) {
        if (digitalZoom <= 1.0f) return
        val cameraId = getCameraIdForLens(currentLens)
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val cropW = (sensorRect.width() / digitalZoom).toInt()
        val cropH = (sensorRect.height() / digitalZoom).toInt()
        val cropX = (sensorRect.width() - cropW) / 2
        val cropY = (sensorRect.height() - cropH) / 2
        val cropRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
    }

    /** Trigger autofocus tap-to-focus at the given normalized coordinates */
    fun triggerTapToFocus(normX: Float, normY: Float) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val cameraId = getCameraIdForLens(currentLens)
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
    fun startVideoRecording() {
        if (isRecording) return
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        mediaRecorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            val res = videoResolution
            setVideoSize(res.width, res.height)
            setVideoFrameRate(frameRate.fps)

            val bitrate = when (res) {
                VideoResolution.UHD_4K -> 50_000_000
                VideoResolution.FHD_1080P -> 20_000_000
                VideoResolution.HD_720P -> 10_000_000
            }
            setVideoEncodingBitRate(bitrate)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(context.getExternalFilesDir(null), "ProCam13")
            dir.mkdirs()
            val outputFile = File(dir, "VID_$timestamp.mp4")
            setOutputFile(outputFile.absolutePath)
            prepare()
        }

        val recorderSurface = mediaRecorder!!.surface
        texture.setDefaultBufferSize(videoResolution.width, videoResolution.height)
        val previewSurface = Surface(texture)

        try {
            camera.createCaptureSession(listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(previewSurface)
                            addTarget(recorderSurface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(frameRate.fps, frameRate.fps))
                            applyProSettings(this)
                        }
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        mediaRecorder?.start()
                        isRecording = true
                        onVideoStateChanged(true)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError("Video session config failed")
                    }
                }, backgroundHandler)
        } catch (e: Exception) {
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
    fun setLens(lens: LensMode) { openCamera(lens) }
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
