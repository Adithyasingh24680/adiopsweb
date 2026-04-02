package com.adiopsweb.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
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
import android.view.Surface
import android.view.TextureView
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "Camera2Handler"

enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    FULL("FULL", 0, 0),
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
    private var isFrontCamera = false
    private var sensorOrientation = 90   // cached when camera opens
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
    private var viewW = 0
    private var viewH = 0

    fun setViewSize(w: Int, h: Int) {
        viewW = w; viewH = h
        if (w > 0 && h > 0) applyPreviewTransform()
    }

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null; backgroundHandler = null
    }

    private val backCameraId: String by lazy {
        getCameraId(CameraCharacteristics.LENS_FACING_BACK, fallback = "0")
    }
    private val frontCameraId: String by lazy {
        getCameraId(CameraCharacteristics.LENS_FACING_FRONT, fallback = "1")
    }

    private fun getCameraId(facing: Int, fallback: String): String =
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: fallback

    private fun activeCameraId() = if (isFrontCamera) frontCameraId else backCameraId

    @SuppressLint("MissingPermission")
    fun openCamera(lens: LensMode = currentLens) {
        currentLens = lens
        closeCamera()
        sensorOrientation = cameraManager.getCameraCharacteristics(activeCameraId())
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        try {
            cameraManager.openCamera(activeCameraId(), object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cameraDevice = camera; createPreviewSession() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null; onError("Camera error $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) { onError("Cannot open camera: ${e.message}") }
    }

    // ── Preview transform ────────────────────────────────────────────────────
    //
    // TextureView default: buffer (bx,by) → view (bx·vW/bufW, by·vH/bufH).
    // setTransform(M) applies M on top. M = S·R in post-concat means R first, S second.
    //
    // For SO=90 (back camera, portrait phone):
    //   scale = max(vW/bufH, vH/bufW)
    //   sx    = scale · bufH / vH   ← bufH (not bufW) because R swaps axes
    //   sy    = scale · bufW / vW
    //   postScale(sx, sy) then postRotate(+90)
    //
    // Verified: buffer corners map to exactly the expected portrait positions.
    //
    private fun applyPreviewTransform() {
        // Use viewW/viewH set from SurfaceTexture callbacks (guaranteed correct dimensions).
        // Fall back to textureView measured size only if not yet set.
        val vW = (if (viewW > 0) viewW else textureView.width).toFloat()
        val vH = (if (viewH > 0) viewH else textureView.height).toFloat()
        if (vW == 0f || vH == 0f) return

        val so = sensorOrientation
        val matrix = Matrix()
        val cx = vW / 2f
        val cy = vH / 2f

        if (so % 180 == 90) {
            // Buffer is landscape, view is portrait.
            // After +SO° rotation the buffer's short edge (bufH) maps to vW axis and
            // long edge (bufW) maps to vH axis, so we scale accordingly.
            val scale = maxOf(vW / bufH.toFloat(), vH / bufW.toFloat())
            matrix.postScale(scale * bufH / vH, scale * bufW / vW, cx, cy)
            matrix.postRotate(so.toFloat(), cx, cy)
        } else {
            val scale = maxOf(vW / bufW.toFloat(), vH / bufH.toFloat())
            matrix.postScale(scale, scale, cx, cy)
            if (so == 180) matrix.postRotate(180f, cx, cy)
        }

        textureView.setTransform(matrix)
    }

    // ── Preview size ─────────────────────────────────────────────────────────

    private fun chooseBestPreviewSize(cameraId: String): Pair<Int, Int> {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Pair(1920, 1080)
        val vW = textureView.width.coerceAtLeast(1)
        val vH = textureView.height.coerceAtLeast(1)
        val targetRatio = vH.toFloat() / vW.toFloat()   // portrait → landscape target
        val best = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width >= 1280 }
            ?.minByOrNull { s -> kotlin.math.abs(s.width.toFloat() / s.height.toFloat() - targetRatio) }
        return if (best != null) Pair(best.width, best.height) else Pair(1920, 1080)
    }

    // ── Session creation ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun createPreviewSession() {
        val texture = textureView.surfaceTexture ?: return
        val (w, h) = chooseBestPreviewSize(backCameraId)
        bufW = w; bufH = h
        texture.setDefaultBufferSize(bufW, bufH)
        val previewSurface = Surface(texture)
        // Apply transform on main thread — viewW/viewH set by setViewSize() from SurfaceTexture callbacks
        android.os.Handler(android.os.Looper.getMainLooper()).post { applyPreviewTransform() }

        imageReader = ImageReader.newInstance(4000, 3000,
            if (isRawEnabled) ImageFormat.RAW_SENSOR else ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage(); savePhoto(img); img.close()
        }, backgroundHandler)

        try {
            cameraDevice?.createCaptureSession(
                listOf(previewSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { captureSession = s; startPreview(previewSurface) }
                    override fun onConfigureFailed(s: CameraCaptureSession) { onError("Preview session failed") }
                }, backgroundHandler)
        } catch (e: CameraAccessException) { onError("Session failed: ${e.message}") }
    }

    private fun startPreview(previewSurface: Surface) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface); applyAllSettings(this)
            }
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) { onError("Preview failed: ${e.message}") }
    }

    // ── Settings applicators ─────────────────────────────────────────────────

    private fun applyAllSettings(b: CaptureRequest.Builder) {
        applyExposure(b); applyWhiteBalance(b); applyFocus(b)
        applyFlash(b); applyZoom(b); applyColorProfile(b); applySceneMode(b)
    }

    private fun applyExposure(b: CaptureRequest.Builder) {
        if (proSettings.iso == 0 && proSettings.shutterUs == 0L) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, proSettings.exposureCompensation)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            if (proSettings.iso > 0) b.set(CaptureRequest.SENSOR_SENSITIVITY, proSettings.iso)
            if (proSettings.shutterUs > 0L)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, proSettings.shutterUs * 1000L)
        }
    }

    private fun applyWhiteBalance(b: CaptureRequest.Builder) {
        if (proSettings.whiteBalance == WhiteBalance.AUTO)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        else
            b.set(CaptureRequest.CONTROL_AWB_MODE, proSettings.whiteBalance.awbMode)
    }

    private fun applyFocus(b: CaptureRequest.Builder) {
        when (proSettings.focusMode) {
            FocusMode.AUTO ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            FocusMode.CONTINUOUS ->
                b.set(CaptureRequest.CONTROL_AF_MODE,
                    if (captureMode == CaptureMode.VIDEO) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            FocusMode.MANUAL -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, proSettings.manualFocusDistance * 10f)
            }
        }
    }

    private fun applyFlash(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.FLASH_MODE,
            if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        val totalZoom = currentLens.zoomFactor * maxOf(digitalZoom, 1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, totalZoom)
        } else if (totalZoom > 1.0f) {
            val chars = cameraManager.getCameraCharacteristics(backCameraId)
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cW = (sensor.width() / totalZoom).toInt()
            val cH = (sensor.height() / totalZoom).toInt()
            val cX = (sensor.width() - cW) / 2; val cY = (sensor.height() - cH) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(cX, cY, cX + cW, cY + cH))
        }
    }

    private fun applyColorProfile(b: CaptureRequest.Builder) {
        try {
            val caps = cameraManager.getCameraCharacteristics(backCameraId)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val hasManualPP = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

            // Always reset effect mode first so profiles don't stack
            b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

            if (hasManualPP) {
                when (colorProfile) {
                    ColorProfile.NATURAL   -> b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
                    ColorProfile.VIVID     -> { b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE); b.set(CaptureRequest.TONEMAP_GAMMA, 1.6f) }
                    ColorProfile.FLAT      -> { b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE); b.set(CaptureRequest.TONEMAP_GAMMA, 3.2f) }
                    ColorProfile.LOG       -> { b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE); b.set(CaptureRequest.TONEMAP_GAMMA, 5.0f) }
                    ColorProfile.FILM_NOIR -> { b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST); b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_MONO) }
                    ColorProfile.FADE      -> { b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE); b.set(CaptureRequest.TONEMAP_GAMMA, 2.8f) }
                    ColorProfile.WARM      -> {
                        b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
                        b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(1.4f, 1.0f, 1.0f, 0.7f))
                    }
                    ColorProfile.COOL      -> {
                        b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
                        b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(0.7f, 1.0f, 1.0f, 1.4f))
                    }
                }
            } else {
                // Fallback: CONTROL_EFFECT_MODE works on every device
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
                when (colorProfile) {
                    ColorProfile.FILM_NOIR -> b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_MONO)
                    ColorProfile.FADE      -> b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE)
                    ColorProfile.FLAT      -> b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_SEPIA)
                    ColorProfile.LOG       -> b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE)
                    ColorProfile.WARM      -> b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
                    ColorProfile.COOL      -> b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
                    else -> { /* NATURAL, VIVID: device default */ }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Color profile: ${e.message}") }
    }

    private fun applySceneMode(b: CaptureRequest.Builder) {
        when (captureMode) {
            CaptureMode.PORTRAIT -> {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
            }
            CaptureMode.VIDEO, CaptureMode.PRO -> {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            else -> {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
            }
        }
        if (isHdrEnabled) {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
        }
    }

    // ── Tap-to-focus ─────────────────────────────────────────────────────────

    fun triggerTapToFocus(normX: Float, normY: Float) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val chars = cameraManager.getCameraCharacteristics(backCameraId)
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val sz = 200
        val x = (normX * sensor.width()).toInt().coerceIn(sz, sensor.width() - sz)
        val y = (normY * sensor.height()).toInt().coerceIn(sz, sensor.height() - sz)
        val rect = MeteringRectangle(x - sz, y - sz, sz * 2, sz * 2, 1000)
        try {
            val texture = textureView.surfaceTexture ?: return
            val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(Surface(texture))
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                applyAllSettings(this)
            }
            session.capture(b.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) { Log.e(TAG, "Tap-to-focus failed", e) }
    }

    // ── Still capture ─────────────────────────────────────────────────────────

    fun capturePhoto() {
        val session = captureSession ?: return
        val reader = imageReader ?: return
        try {
            val b = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, 95)
                set(CaptureRequest.JPEG_ORIENTATION, 90)
                applyAllSettings(this)
            }
            session.capture(b.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) { onError("Capture failed: ${e.message}") }
    }

    private fun savePhoto(image: android.media.Image) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PROCAM_$ts.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ProCam13")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes); out.write(bytes)
            }
            onPhotoSaved("PROCAM_$ts.jpg")
        }
    }

    // ── Video recording ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun startVideoRecording() {
        if (isRecording) return
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        val isHighSpeed = frameRate.fps >= 120
        if (isHighSpeed) {
            startHighSpeedRecording(camera, texture)
        } else {
            startStandardRecording(camera, texture)
        }
    }

    @Suppress("DEPRECATION")
    private fun startStandardRecording(camera: CameraDevice, texture: android.graphics.SurfaceTexture) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_$ts.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProCam13")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: run { onError("Cannot create video file"); return }
        val fd = context.contentResolver.openFileDescriptor(uri, "w")
            ?: run { onError("Cannot open video fd"); return }

        val res = videoResolution
        val fps = frameRate.fps

        mediaRecorder = makeRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(res.width, res.height)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(bitrateFor(res))
            setOutputFile(fd.fileDescriptor)
            prepare()
        }

        val recSurface = mediaRecorder!!.surface
        texture.setDefaultBufferSize(res.width, res.height)
        val previewSurface = Surface(texture)

        try {
            camera.createCaptureSession(listOf(previewSurface, recSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(previewSurface); addTarget(recSurface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                                applyAllSettings(this)
                            }
                            session.setRepeatingRequest(b.build(), null, backgroundHandler)
                            mediaRecorder?.start(); isRecording = true; fd.close()
                            onVideoStateChanged(true)
                        } catch (e: Exception) { fd.close(); onError("Record start: ${e.message}") }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { fd.close(); onError("Video session failed") }
                }, backgroundHandler)
        } catch (e: Exception) { fd.close(); onError("Start recording: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun startHighSpeedRecording(camera: CameraDevice, texture: android.graphics.SurfaceTexture) {
        val chars = cameraManager.getCameraCharacteristics(backCameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val hsSizes = map?.highSpeedVideoSizes
        val targetFps = frameRate.fps

        if (hsSizes.isNullOrEmpty()) {
            // Device doesn't support high-speed — fall back to standard at 60fps
            Log.w(TAG, "High-speed not supported, falling back to 60fps")
            val origFps = frameRate
            frameRate = FrameRate.FPS_60
            startStandardRecording(camera, texture)
            frameRate = origFps
            return
        }

        // Pick the largest high-speed size ≤ selected resolution
        val hsSize = hsSizes
            .filter { it.width <= videoResolution.width || videoResolution == VideoResolution.UHD_4K }
            .maxByOrNull { it.width * it.height }
            ?: hsSizes.first()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_HS_${targetFps}fps_$ts.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProCam13")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: run { onError("Cannot create video file"); return }
        val fd = context.contentResolver.openFileDescriptor(uri, "w")
            ?: run { onError("Cannot open video fd"); return }

        mediaRecorder = makeRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(hsSize.width, hsSize.height)
            setVideoFrameRate(targetFps)
            setVideoEncodingBitRate(targetFps * hsSize.width * hsSize.height / 10)
            setCaptureRate(targetFps.toDouble())
            setOutputFile(fd.fileDescriptor)
            prepare()
        }

        val recSurface = mediaRecorder!!.surface
        texture.setDefaultBufferSize(hsSize.width, hsSize.height)
        val previewSurface = Surface(texture)

        try {
            camera.createConstrainedHighSpeedCaptureSession(
                listOf(previewSurface, recSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val hsSession = session as CameraConstrainedHighSpeedCaptureSession
                            val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(previewSurface); addTarget(recSurface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    Range(targetFps, targetFps))
                            }
                            val requests = hsSession.createHighSpeedRequestList(b.build())
                            session.setRepeatingBurst(requests, null, backgroundHandler)
                            mediaRecorder?.start(); isRecording = true; fd.close()
                            onVideoStateChanged(true)
                        } catch (e: Exception) { fd.close(); onError("HS record start: ${e.message}") }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { fd.close(); onError("HS session failed") }
                }, backgroundHandler)
        } catch (e: Exception) { fd.close(); onError("HS start failed: ${e.message}") }
    }

    private fun makeRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    private fun bitrateFor(res: VideoResolution) = when (res) {
        VideoResolution.UHD_4K    -> 60_000_000
        VideoResolution.FHD_1080P -> 20_000_000
        VideoResolution.HD_720P   ->  8_000_000
    }

    fun stopVideoRecording() {
        if (!isRecording) return
        try {
            captureSession?.stopRepeating()
            mediaRecorder?.apply { stop(); reset(); release() }
            mediaRecorder = null; isRecording = false
            onVideoStateChanged(false)
            openCamera(currentLens)
        } catch (e: Exception) { onError("Stop recording: ${e.message}") }
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
    fun setProSettings(s: ProSettings) { proSettings = s; restartPreview() }
    fun setColorProfile(p: ColorProfile) { colorProfile = p; restartPreview() }
    fun setAspectRatio(r: AspectRatio) { aspectRatio = r }
    fun setFlash(on: Boolean) { isFlashOn = on; restartPreview() }
    fun setHdr(on: Boolean) { isHdrEnabled = on; restartPreview() }
    fun setRaw(on: Boolean) { isRawEnabled = on }
    fun setDigitalZoom(z: Float) { digitalZoom = z.coerceIn(1f, 10f); restartPreview() }
    fun isCurrentlyRecording() = isRecording
    fun getCurrentLens() = currentLens

    private fun restartPreview() {
        val texture = textureView.surfaceTexture ?: return
        startPreview(Surface(texture))
    }
}
