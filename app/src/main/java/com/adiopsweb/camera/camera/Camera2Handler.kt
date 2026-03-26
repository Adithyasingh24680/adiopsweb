package com.adiopsweb.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
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
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "Camera2Handler"

enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    FULL("FULL", 0, 0),    // no crop
    R16_9("16:9", 16, 9),
    R4_3("4:3", 4, 3),
    R1_1("1:1", 1, 1)
}

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
    private var videoResolution = VideoResolution.FHD_1080P
    private var frameRate = FrameRate.FPS_30
    private var proSettings = ProSettings()
    private var colorProfile = ColorProfile.NATURAL
    private var aspectRatio = AspectRatio.FULL
    private var isRecording = false
    private var isFlashOn = false
    private var isHdrEnabled = false
    private var isRawEnabled = false
    private var digitalZoom = 1.0f
    private var bufW = 1920
    private var bufH = 1080

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (e: InterruptedException) { }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun getBackCameraId(): String {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"
    }

    @SuppressLint("MissingPermission")
    fun openCamera(lens: LensMode = currentLens) {
        currentLens = lens
        closeCamera()
        val cameraId = getBackCameraId()
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    onError("Camera error $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError("Cannot open camera: ${e.message}")
        }
    }

    // ── Preview size (landscape native) ─────────────────────────────────────

    private fun chooseBestPreviewSize(cameraId: String): Pair<Int, Int> {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Pair(1920, 1080)
        val vW = textureView.width.coerceAtLeast(1)
        val vH = textureView.height.coerceAtLeast(1)
        // View is portrait; landscape target ratio = vH/vW
        val targetRatio = vH.toFloat() / vW.toFloat()
        val best = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width >= 1280 }
            ?.minByOrNull { s -> kotlin.math.abs(s.width.toFloat() / s.height.toFloat() - targetRatio) }
        return if (best != null) Pair(best.width, best.height) else Pair(1920, 1080)
    }

    /**
     * Correct Camera2 TextureView transform for portrait display.
     *
     * The camera outputs a landscape buffer (bufW × bufH).
     * The TextureView is portrait (vW × vH).
     * SENSOR_ORIENTATION = 90 on OnePlus 13 back camera.
     *
     * Correct sequence (mathematically derived):
     *   1. postScale(scale * bufW / vW,  scale * bufH / vH)   ← un-stretch + fill scale
     *   2. postRotate(sensorOrientation)                        ← correct orientation
     */
    private fun applyPreviewTransform() {
        val vW = textureView.width.toFloat()
        val vH = textureView.height.toFloat()
        if (vW == 0f || vH == 0f) return

        val chars = cameraManager.getCameraCharacteristics(getBackCameraId())
        val so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val matrix = Matrix()
        val cx = vW / 2f
        val cy = vH / 2f

        if (so % 180 == 90) {
            // Landscape buffer → portrait view: effective portrait dims = bufH × bufW
            val scale = maxOf(vW / bufH.toFloat(), vH / bufW.toFloat())
            matrix.postScale(scale * bufW / vW, scale * bufH / vH, cx, cy)
        } else {
            // Buffer already portrait-ish
            val scale = maxOf(vW / bufW.toFloat(), vH / bufH.toFloat())
            matrix.postScale(scale * bufW / vW, scale * bufH / vH, cx, cy)
        }
        matrix.postRotate(so.toFloat(), cx, cy)
        textureView.setTransform(matrix)
    }

    // ── Session creation ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun createPreviewSession() {
        val texture = textureView.surfaceTexture ?: return
        val (w, h) = chooseBestPreviewSize(getBackCameraId())
        bufW = w; bufH = h
        texture.setDefaultBufferSize(bufW, bufH)
        val previewSurface = Surface(texture)
        android.os.Handler(android.os.Looper.getMainLooper()).post { applyPreviewTransform() }

        val format = if (isRawEnabled) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
        imageReader = ImageReader.newInstance(4000, 3000, format, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            savePhoto(image)
            image.close()
        }, backgroundHandler)

        try {
            cameraDevice?.createCaptureSession(
                listOf(previewSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview(previewSurface)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError("Preview session config failed")
                    }
                }, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError("Session creation failed: ${e.message}")
        }
    }

    private fun startPreview(previewSurface: Surface) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                applyAllSettings(this)
            }
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError("Preview failed: ${e.message}")
        }
    }

    // ── Unified settings applicator ──────────────────────────────────────────

    private fun applyAllSettings(builder: CaptureRequest.Builder) {
        applyExposure(builder)
        applyWhiteBalance(builder)
        applyFocus(builder)
        applyFlash(builder)
        applyZoom(builder)
        applyColorProfile(builder)
        applySceneMode(builder)
    }

    private fun applyExposure(builder: CaptureRequest.Builder) {
        if (proSettings.iso == 0 && proSettings.shutterUs == 0L) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                proSettings.exposureCompensation)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            if (proSettings.iso > 0)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, proSettings.iso)
            if (proSettings.shutterUs > 0L)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, proSettings.shutterUs * 1000L)
        }
    }

    private fun applyWhiteBalance(builder: CaptureRequest.Builder) {
        if (proSettings.whiteBalance == WhiteBalance.AUTO) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, proSettings.whiteBalance.awbMode)
        }
    }

    private fun applyFocus(builder: CaptureRequest.Builder) {
        when (proSettings.focusMode) {
            FocusMode.AUTO ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            FocusMode.CONTINUOUS ->
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            FocusMode.MANUAL -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE,
                    proSettings.manualFocusDistance * 10f)
            }
        }
    }

    private fun applyFlash(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.FLASH_MODE,
            if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val totalZoom = currentLens.zoomFactor * maxOf(digitalZoom, 1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, totalZoom)
        } else {
            if (totalZoom > 1.0f) applyDigitalZoom(builder, totalZoom)
        }
    }

    private fun applyDigitalZoom(builder: CaptureRequest.Builder, zoom: Float) {
        val chars = cameraManager.getCameraCharacteristics(getBackCameraId())
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val cropW = (sensorRect.width() / zoom).toInt()
        val cropH = (sensorRect.height() / zoom).toInt()
        val cropX = (sensorRect.width() - cropW) / 2
        val cropY = (sensorRect.height() - cropH) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION,
            Rect(cropX, cropY, cropX + cropW, cropY + cropH))
    }

    /**
     * Apply color profiles using Camera2 tonemap control.
     * Requires MANUAL_POST_PROCESSING capability; silently skipped if unsupported.
     */
    private fun applyColorProfile(builder: CaptureRequest.Builder) {
        try {
            val chars = cameraManager.getCameraCharacteristics(getBackCameraId())
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return
            val hasManualPP = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

            if (hasManualPP) {
                when (colorProfile) {
                    ColorProfile.NATURAL -> {
                        builder.set(CaptureRequest.TONEMAP_MODE,
                            CameraMetadata.TONEMAP_MODE_FAST)
                    }
                    ColorProfile.VIVID -> {
                        builder.set(CaptureRequest.TONEMAP_MODE,
                            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                        builder.set(CaptureRequest.TONEMAP_GAMMA, 1.6f)
                    }
                    ColorProfile.FLAT -> {
                        builder.set(CaptureRequest.TONEMAP_MODE,
                            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                        builder.set(CaptureRequest.TONEMAP_GAMMA, 3.2f)
                    }
                    ColorProfile.LOG -> {
                        builder.set(CaptureRequest.TONEMAP_MODE,
                            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                        builder.set(CaptureRequest.TONEMAP_GAMMA, 5.0f)
                    }
                    ColorProfile.WARM -> {
                        builder.set(CaptureRequest.TONEMAP_MODE,
                            CameraMetadata.TONEMAP_MODE_FAST)
                        builder.set(CaptureRequest.COLOR_CORRECTION_MODE,
                            CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS,
                            RggbChannelVector(1.4f, 1.0f, 1.0f, 0.7f))
                    }
                    ColorProfile.COOL -> {
                        builder.set(CaptureRequest.TONEMAP_MODE,
                            CameraMetadata.TONEMAP_MODE_FAST)
                        builder.set(CaptureRequest.COLOR_CORRECTION_MODE,
                            CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS,
                            RggbChannelVector(0.7f, 1.0f, 1.0f, 1.4f))
                    }
                    ColorProfile.FILM_NOIR -> {
                        builder.set(CaptureRequest.TONEMAP_MODE,
                            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                        builder.set(CaptureRequest.TONEMAP_GAMMA, 1.3f)
                    }
                    ColorProfile.FADE -> {
                        builder.set(CaptureRequest.TONEMAP_MODE,
                            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                        builder.set(CaptureRequest.TONEMAP_GAMMA, 2.8f)
                    }
                }
            } else {
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Color profile not applied: ${e.message}")
        }
    }

    /**
     * Portrait mode: use SCENE_MODE_FACE_PRIORITY for face detection + AE,
     * plus short focus distance for simulated shallow depth-of-field.
     * All other modes: SCENE_MODE_DISABLED.
     */
    private fun applySceneMode(builder: CaptureRequest.Builder) {
        when (captureMode) {
            CaptureMode.PORTRAIT -> {
                builder.set(CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                // Simulate shallow DOF: continuous AF on face, let ISP do the rest
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            }
            CaptureMode.VIDEO -> {
                builder.set(CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                // Use continuous video AF for smooth focus pulls
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                // Video stabilization
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
        }
        if (isHdrEnabled && captureMode != CaptureMode.PORTRAIT) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE,
                CaptureRequest.CONTROL_SCENE_MODE_HDR)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        }
    }

    // ── Tap-to-focus ─────────────────────────────────────────────────────────

    fun triggerTapToFocus(normX: Float, normY: Float) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val chars = cameraManager.getCameraCharacteristics(getBackCameraId())
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val focusSize = 200
        val x = (normX * sensorRect.width()).toInt().coerceIn(focusSize, sensorRect.width() - focusSize)
        val y = (normY * sensorRect.height()).toInt().coerceIn(focusSize, sensorRect.height() - focusSize)
        val focusRect = MeteringRectangle(x - focusSize, y - focusSize, focusSize * 2, focusSize * 2, 1000)

        try {
            val texture = textureView.surfaceTexture ?: return
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(Surface(texture))
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                applyAllSettings(this)
            }
            session.capture(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Tap-to-focus failed", e)
        }
    }

    // ── Still capture ─────────────────────────────────────────────────────────

    fun capturePhoto() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, 95)
                set(CaptureRequest.JPEG_ORIENTATION, 90) // correct JPEG EXIF orientation
                applyAllSettings(this)
            }
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) { Log.d(TAG, "Photo captured") }
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
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                stream.write(bytes)
            }
            onPhotoSaved(filename)
        }
    }

    // ── Video recording ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun startVideoRecording() {
        if (isRecording) return
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        // Clamp resolution: 4K requires vendor CamcorderProfile; cap at 1080p for compatibility
        val safeRes = if (videoResolution == VideoResolution.UHD_4K) VideoResolution.FHD_1080P
                      else videoResolution
        // High-speed (120/240fps) needs ConstrainedHighSpeed session; cap standard at 60
        val safeFps = frameRate.fps.coerceAtMost(60)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_$timestamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProCam13")
        }
        val videoUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, outputValues)
            ?: run { onError("Cannot create video file"); return }

        val videoFd = context.contentResolver.openFileDescriptor(videoUri, "w")
            ?: run { onError("Cannot open video file"); return }

        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        ).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(safeRes.width, safeRes.height)
            setVideoFrameRate(safeFps)
            setVideoEncodingBitRate(when (safeRes) {
                VideoResolution.UHD_4K    -> 50_000_000
                VideoResolution.FHD_1080P -> 20_000_000
                VideoResolution.HD_720P   ->  8_000_000
            })
            setOutputFile(videoFd.fileDescriptor)
            prepare()
        }

        val recorderSurface = mediaRecorder!!.surface
        texture.setDefaultBufferSize(safeRes.width, safeRes.height)
        val previewSurface = Surface(texture)

        try {
            camera.createCaptureSession(listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(previewSurface)
                                addTarget(recorderSurface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    Range(safeFps.coerceAtLeast(24), safeFps))
                                applyAllSettings(this)
                            }
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            mediaRecorder?.start()
                            isRecording = true
                            videoFd.close()
                            onVideoStateChanged(true)
                        } catch (e: Exception) {
                            videoFd.close()
                            onError("Record start failed: ${e.message}")
                        }
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

    fun stopVideoRecording() {
        if (!isRecording) return
        try {
            captureSession?.stopRepeating()
            mediaRecorder?.apply { stop(); reset(); release() }
            mediaRecorder = null
            isRecording = false
            onVideoStateChanged(false)
            openCamera(currentLens)
        } catch (e: Exception) {
            onError("Stop recording failed: ${e.message}")
        }
    }

    fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setLens(lens: LensMode) { currentLens = lens; restartPreview() }
    fun setCaptureMode(mode: CaptureMode) { captureMode = mode; restartPreview() }
    fun setVideoResolution(res: VideoResolution) { videoResolution = res }
    fun setFrameRate(fps: FrameRate) { frameRate = fps }
    fun setProSettings(settings: ProSettings) { proSettings = settings; restartPreview() }
    fun setColorProfile(profile: ColorProfile) { colorProfile = profile; restartPreview() }
    fun setAspectRatio(ratio: AspectRatio) { aspectRatio = ratio; restartPreview() }
    fun setFlash(on: Boolean) { isFlashOn = on; restartPreview() }
    fun setHdr(on: Boolean) { isHdrEnabled = on; restartPreview() }
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
