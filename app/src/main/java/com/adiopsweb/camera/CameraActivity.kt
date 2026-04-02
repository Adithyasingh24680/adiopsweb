package com.adiopsweb.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.adiopsweb.camera.camera.*
import com.adiopsweb.camera.databinding.ActivityCameraBinding
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

class CameraActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraHandler: Camera2Handler
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var captureMode = CaptureMode.PHOTO
    private var currentLens = LensMode.WIDE
    private var videoResolution = VideoResolution.FHD_1080P
    private var frameRate = FrameRate.FPS_30
    private var proSettings = ProSettings()
    private var colorProfile = ColorProfile.NATURAL
    private var aspectRatio = AspectRatio.FULL
    private var gridType = GridType.OFF
    private var isFlashOn = false
    private var isHdrOn = false
    private var isRawOn = false
    private var showHistogram = false
    private var showLevel = true

    // ISO index for cycling
    private var isoIndex = 0
    private var shutterIndex = 0
    private var wbIndex = 0
    private var evValue = 0
    private var focusIndex = 1  // default C-AF
    private var resIndex = 1    // default 1080p
    private var fpsIndex = 1    // default 30fps
    private var stabOn = true

    // Slider panel state
    private enum class ActiveSlider { ISO, SHUTTER, EV, WB, FOCUS }
    private var activeSlider: ActiveSlider? = null

    // Zoom
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var currentZoom = 1.0f

    // Recording timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recSeconds = 0
    private val timerRunnable = object : Runnable {
        override fun run() {
            recSeconds++
            binding.tvRecordTimer.text =
                String.format(Locale.US, "%02d:%02d", recSeconds / 60, recSeconds % 60)
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (!hasPermissions()) { requestPermissions(); return }
        initCamera(); initSensors(); setupUI()
    }

    override fun onResume() {
        super.onResume()
        cameraHandler.startBackgroundThread()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (binding.textureView.isAvailable) cameraHandler.openCamera(currentLens)
    }

    override fun onPause() {
        timerHandler.removeCallbacks(timerRunnable)
        cameraHandler.closeCamera()
        cameraHandler.stopBackgroundThread()
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasPermissions() = arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
            initCamera(); initSensors(); setupUI()
        } else { Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show(); finish() }
    }

    // ── Camera init ───────────────────────────────────────────────────────────

    private fun initCamera() {
        cameraHandler = Camera2Handler(
            context = this,
            textureView = binding.textureView,
            onError = { msg -> runOnUiThread { showError(msg) } },
            onPhotoSaved = { name -> runOnUiThread { showSnack("Saved: $name") } },
            onVideoStateChanged = { rec -> runOnUiThread { onRecordingStateChanged(rec) } }
        )
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                cameraHandler.setViewSize(w, h)
                cameraHandler.openCamera(currentLens)
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                cameraHandler.setViewSize(w, h)
            }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    // ── Sensors ───────────────────────────────────────────────────────────────

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val angle = Math.toDegrees(
                Math.atan2(event.values[0].toDouble(), event.values[1].toDouble())).toFloat()
            binding.overlayView.levelAngle = angle
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupUI() {
        setupTopBar()
        setupLensButtons()
        setupModeSelector()
        setupCaptureButton()
        setupGalleryButton()
        setupControlStrip()
        setupAspectRatio()
        setupPinchZoom()
        setupTapToFocus()
    }

    // ── Top bar ───────────────────────────────────────────────────────────────

    private fun setupTopBar() {
        binding.btnFlash.setOnClickListener {
            isFlashOn = !isFlashOn
            cameraHandler.setFlash(isFlashOn)
            binding.btnFlash.alpha = if (isFlashOn) 1f else 0.55f
        }
        binding.btnHdr.setOnClickListener {
            isHdrOn = !isHdrOn
            cameraHandler.setHdr(isHdrOn)
            binding.btnHdr.alpha = if (isHdrOn) 1f else 0.55f
        }
        binding.btnRaw.setOnClickListener {
            isRawOn = !isRawOn
            cameraHandler.setRaw(isRawOn)
            binding.btnRaw.text = if (isRawOn) "RAW" else "JPG"
            binding.btnRaw.alpha = if (isRawOn) 1f else 0.55f
        }
        // Grid type: cycle OFF → 3×3 → GOLDEN → 9×9 → OFF
        binding.btnGridType.setOnClickListener {
            val types = GridType.values()
            gridType = types[(gridType.ordinal + 1) % types.size]
            binding.overlayView.gridType = gridType
            binding.btnGridType.text = gridType.label
            binding.btnGridType.alpha = if (gridType != GridType.OFF) 1f else 0.55f
        }
        // Histogram toggle
        binding.btnHistogram.setOnClickListener {
            showHistogram = !showHistogram
            binding.overlayView.showHistogram = showHistogram
            binding.btnHistogram.alpha = if (showHistogram) 1f else 0.55f
        }
        binding.btnMenu.setOnClickListener {
            showSnack("Swipe the control strip for all settings →")
        }
        binding.btnFlipCamera.setOnClickListener {
            showSnack("Front camera: tap lens selector to switch")
        }
    }

    // ── Lens ──────────────────────────────────────────────────────────────────

    private fun setupLensButtons() {
        binding.btnLensUltrawide.setOnClickListener { switchLens(LensMode.ULTRAWIDE) }
        binding.btnLensWide.setOnClickListener { switchLens(LensMode.WIDE) }
        binding.btnLensTele.setOnClickListener { switchLens(LensMode.TELEPHOTO) }
        updateLensButtons()
    }

    private fun switchLens(lens: LensMode) {
        if (cameraHandler.isCurrentlyRecording()) return
        currentLens = lens; currentZoom = 1f
        cameraHandler.setLens(lens); cameraHandler.setDigitalZoom(1f)
        binding.overlayView.currentLens = lens
        updateLensButtons()
        showSnack("${lens.focalLengthMm}mm · f${lens.aperture}")
    }

    private fun updateLensButtons() {
        val orange = 0xFFFF8000.toInt(); val dim = 0x88FFFFFF.toInt()
        listOf(binding.btnLensUltrawide to LensMode.ULTRAWIDE,
               binding.btnLensWide to LensMode.WIDE,
               binding.btnLensTele to LensMode.TELEPHOTO)
            .forEach { (btn, lens) ->
                btn.setTextColor(if (lens == currentLens) orange else dim)
                btn.textSize = if (lens == currentLens) 14f else 12f
            }
    }

    // ── Mode tabs ─────────────────────────────────────────────────────────────

    private fun setupModeSelector() {
        binding.tabPro.setOnClickListener { setMode(CaptureMode.PRO) }
        binding.tabCine.setOnClickListener { setCinematicMode() }
        setMode(CaptureMode.PRO)
    }

    private fun setMode(mode: CaptureMode) {
        captureMode = mode
        cameraHandler.setCaptureMode(mode)
        binding.portraitPanel.visibility = View.GONE
        val orange = 0xFFFF8000.toInt(); val dim = 0x55FFFFFF.toInt()
        binding.tabPro.setTextColor(if (mode == CaptureMode.PRO) orange else dim)
        binding.tabCine.setTextColor(dim)
        updateCaptureButton()
    }

    private fun setCinematicMode() {
        captureMode = CaptureMode.VIDEO
        cameraHandler.setCaptureMode(CaptureMode.VIDEO)
        frameRate = FrameRate.FPS_24; cameraHandler.setFrameRate(FrameRate.FPS_24)
        colorProfile = ColorProfile.LOG; cameraHandler.setColorProfile(ColorProfile.LOG)
        setAspectRatio(AspectRatio.R16_9)
        binding.btnCtrlFps.text = "24"
        binding.btnCtrlProfile.text = "Log"
        val orange = 0xFFFF8000.toInt(); val dim = 0x55FFFFFF.toInt()
        binding.tabPro.setTextColor(dim)
        binding.tabCine.setTextColor(orange)
        updateCaptureButton()
        showSnack("Cinematic: 24fps · Log · 16:9")
    }

    private fun updateCaptureButton() {
        binding.btnCapture.setImageResource(
            if (captureMode == CaptureMode.VIDEO) R.drawable.ic_record else R.drawable.ic_shutter)
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    private fun setupCaptureButton() {
        binding.btnCapture.setOnClickListener {
            if (captureMode == CaptureMode.VIDEO) {
                if (cameraHandler.isCurrentlyRecording()) cameraHandler.stopVideoRecording()
                else cameraHandler.startVideoRecording()
            } else {
                cameraHandler.capturePhoto(); animateCapture()
            }
        }
    }

    private fun animateCapture() {
        binding.captureFlash.visibility = View.VISIBLE
        binding.captureFlash.animate().alpha(0f).setDuration(180).withEndAction {
            binding.captureFlash.visibility = View.GONE
            binding.captureFlash.alpha = 0.8f
        }.start()
    }

    private fun onRecordingStateChanged(recording: Boolean) {
        binding.btnCapture.setImageResource(
            if (recording) R.drawable.ic_stop else R.drawable.ic_record)
        binding.recordingIndicator.isVisible = recording
        binding.tvRecordTimer.isVisible = recording
        // Lock controls during recording
        val enabled = !recording
        listOf(binding.btnCtrlRes, binding.btnCtrlFps,
               binding.btnLensUltrawide, binding.btnLensWide, binding.btnLensTele)
            .forEach { it.isEnabled = enabled }
        if (recording) {
            recSeconds = 0; binding.tvRecordTimer.text = "00:00"
            timerHandler.postDelayed(timerRunnable, 1000)
        } else {
            timerHandler.removeCallbacks(timerRunnable)
        }
    }

    // ── Gallery ───────────────────────────────────────────────────────────────

    private fun setupGalleryButton() {
        binding.ivGallery.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" })
        }
    }

    // ══ UNIFIED CONTROL STRIP ════════════════════════════════════════════════
    // Each button cycles through its options on tap.
    // All controls visible in all modes; always accessible by scrolling.

    private fun setupControlStrip() {
        setupIsoControl()
        setupShutterControl()
        setupWbControl()
        setupEvControl()
        setupFocusControl()
        setupProfileControl()
        setupResControl()
        setupFpsControl()
        setupStabControl()
        setupLevelControl()
    }

    // ── Slider panel helper ───────────────────────────────────────────────────
    private fun toggleSlider(
        which: ActiveSlider,
        label: String,
        max: Int,
        current: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        if (activeSlider == which) {
            binding.sliderPanel.visibility = View.GONE
            activeSlider = null
            return
        }
        activeSlider = which
        binding.tvSliderLabel.text = label
        binding.sliderControl.max = max
        binding.sliderControl.progress = current.coerceIn(0, max)
        binding.tvSliderValue.text = format(current.coerceIn(0, max))
        binding.sliderControl.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvSliderValue.text = format(progress)
                onChange(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.sliderPanel.visibility = View.VISIBLE
    }

    // ISO: tap chip → slider 0-8 (Auto/50/100/.../6400)
    private fun setupIsoControl() {
        val vals = ISO_VALUES
        binding.btnCtrlIso.setOnClickListener {
            toggleSlider(ActiveSlider.ISO, "ISO", vals.size - 1, isoIndex,
                { i -> if (vals[i] == 0) "Auto" else "${vals[i]}" }
            ) { i ->
                isoIndex = i
                proSettings = proSettings.copy(iso = vals[i])
                cameraHandler.setProSettings(proSettings)
                binding.btnCtrlIso.text = if (vals[i] == 0) "Auto" else "${vals[i]}"
            }
        }
    }

    // Shutter: tap chip → slider over all SHUTTER_SPEEDS
    private fun setupShutterControl() {
        binding.btnCtrlShutter.setOnClickListener {
            toggleSlider(ActiveSlider.SHUTTER, "SHUTTER", SHUTTER_SPEEDS.size - 1, shutterIndex,
                { i -> SHUTTER_SPEEDS[i].first }
            ) { i ->
                shutterIndex = i
                proSettings = proSettings.copy(shutterUs = SHUTTER_SPEEDS[i].second)
                cameraHandler.setProSettings(proSettings)
                binding.btnCtrlShutter.text = SHUTTER_SPEEDS[i].first
            }
        }
    }

    // White balance: tap chip → slider over WB presets
    private fun setupWbControl() {
        val vals = WhiteBalance.values()
        binding.btnCtrlWb.setOnClickListener {
            toggleSlider(ActiveSlider.WB, "WHITE BAL", vals.size - 1, wbIndex,
                { i -> vals[i].label }
            ) { i ->
                wbIndex = i
                proSettings = proSettings.copy(whiteBalance = vals[i])
                cameraHandler.setProSettings(proSettings)
                binding.btnCtrlWb.text = vals[i].label.take(5)
            }
        }
    }

    // EV: tap chip → slider -6 to +6 (SeekBar 0-12, offset by 6)
    private fun setupEvControl() {
        binding.btnCtrlEv.setOnClickListener {
            toggleSlider(ActiveSlider.EV, "EV COMP", 12, evValue + 6,
                { p -> val v = p - 6; if (v >= 0) "+$v" else "$v" }
            ) { p ->
                evValue = p - 6
                proSettings = proSettings.copy(exposureCompensation = evValue)
                cameraHandler.setProSettings(proSettings)
                binding.btnCtrlEv.text = if (evValue >= 0) "+$evValue" else "$evValue"
            }
        }
    }

    // Focus: tap chip → slider over AF/C-AF/MF modes
    private fun setupFocusControl() {
        val vals = FocusMode.values()
        binding.btnCtrlFocus.setOnClickListener {
            toggleSlider(ActiveSlider.FOCUS, "FOCUS", vals.size - 1, focusIndex,
                { i -> vals[i].label }
            ) { i ->
                focusIndex = i
                proSettings = proSettings.copy(focusMode = vals[i])
                cameraHandler.setProSettings(proSettings)
                binding.btnCtrlFocus.text = vals[i].label
            }
        }
    }

    // Color profile cycle
    private fun setupProfileControl() {
        val vals = ColorProfile.values()
        binding.btnCtrlProfile.setOnClickListener {
            colorProfile = vals[(colorProfile.ordinal + 1) % vals.size]
            cameraHandler.setColorProfile(colorProfile)
            binding.btnCtrlProfile.text = colorProfile.label.take(5)
        }
    }

    // Resolution cycle: 720p → 1080p → 4K → 720p
    private fun setupResControl() {
        val vals = VideoResolution.values()
        binding.btnCtrlRes.setOnClickListener {
            if (cameraHandler.isCurrentlyRecording()) return@setOnClickListener
            resIndex = (resIndex + 1) % vals.size
            videoResolution = vals[resIndex]
            cameraHandler.setVideoResolution(videoResolution)
            binding.btnCtrlRes.text = videoResolution.label
        }
    }

    // FPS cycle: 24 → 30 → 60 → 120 → 240 → 24
    private fun setupFpsControl() {
        val vals = FrameRate.values()
        binding.btnCtrlFps.setOnClickListener {
            if (cameraHandler.isCurrentlyRecording()) return@setOnClickListener
            fpsIndex = (fpsIndex + 1) % vals.size
            frameRate = vals[fpsIndex]
            cameraHandler.setFrameRate(frameRate)
            binding.btnCtrlFps.text = "${frameRate.fps}"
            if (frameRate.fps >= 120)
                showSnack("${frameRate.fps}fps · High-speed slow-motion")
        }
    }

    // Video stabilization toggle
    private fun setupStabControl() {
        binding.btnCtrlStab.setOnClickListener {
            stabOn = !stabOn
            binding.btnCtrlStab.text = if (stabOn) "ON" else "OFF"
            binding.btnCtrlStab.setTextColor(
                if (stabOn) 0xFFFF8000.toInt() else 0x88FFFFFF.toInt())
            showSnack("Stabilization ${if (stabOn) "on" else "off"}")
        }
    }

    // Level indicator toggle
    private fun setupLevelControl() {
        binding.btnCtrlLevel.setOnClickListener {
            showLevel = !showLevel
            binding.overlayView.showLevel = showLevel
            binding.btnCtrlLevel.text = if (showLevel) "ON" else "OFF"
            binding.btnCtrlLevel.setTextColor(
                if (showLevel) 0xFFFF8000.toInt() else 0x88FFFFFF.toInt())
        }
    }

    // ── Aspect ratio ──────────────────────────────────────────────────────────

    private fun setupAspectRatio() {
        binding.btnRatioFull.setOnClickListener { setAspectRatio(AspectRatio.FULL) }
        binding.btnRatioWide.setOnClickListener { setAspectRatio(AspectRatio.R16_9) }
        binding.btnRatioStandard.setOnClickListener { setAspectRatio(AspectRatio.R4_3) }
        binding.btnRatioSquare.setOnClickListener { setAspectRatio(AspectRatio.R1_1) }
        updateAspectRatioButtons()
    }

    private fun setAspectRatio(ratio: AspectRatio) {
        aspectRatio = ratio; cameraHandler.setAspectRatio(ratio)
        binding.overlayView.aspectRatio = ratio; updateAspectRatioButtons()
    }

    private fun updateAspectRatioButtons() {
        val orange = 0xFFFF8000.toInt(); val dim = 0x88FFFFFF.toInt()
        binding.btnRatioFull.setTextColor(if (aspectRatio == AspectRatio.FULL) orange else dim)
        binding.btnRatioWide.setTextColor(if (aspectRatio == AspectRatio.R16_9) orange else dim)
        binding.btnRatioStandard.setTextColor(if (aspectRatio == AspectRatio.R4_3) orange else dim)
        binding.btnRatioSquare.setTextColor(if (aspectRatio == AspectRatio.R1_1) orange else dim)
    }

    // ── Pinch-to-zoom ─────────────────────────────────────────────────────────

    private fun setupPinchZoom() {
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    currentZoom = (currentZoom * d.scaleFactor).coerceIn(1f, 10f)
                    cameraHandler.setDigitalZoom(currentZoom)
                    binding.tvZoomLevel.text = String.format(Locale.US, "%.1f×", currentZoom)
                    binding.tvZoomLevel.visibility = View.VISIBLE
                    binding.tvZoomLevel.removeCallbacks(hideZoom)
                    binding.tvZoomLevel.postDelayed(hideZoom, 2000)
                    return true
                }
            })
    }

    private val hideZoom = Runnable { binding.tvZoomLevel.visibility = View.GONE }

    // ── Tap-to-focus ──────────────────────────────────────────────────────────

    private fun setupTapToFocus() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val nx = e.x / binding.textureView.width
                val ny = e.y / binding.textureView.height
                cameraHandler.triggerTapToFocus(nx, ny)
                binding.overlayView.focusPoint = PointF(e.x, e.y)
                binding.overlayView.focusLocked = false
                binding.overlayView.removeCallbacks(clearFocus)
                binding.overlayView.postDelayed(clearFocus, 2000)
                return true
            }
        })
        binding.textureView.setOnTouchListener { v, event ->
            scaleGestureDetector?.onTouchEvent(event)
            gd.onTouchEvent(event)
            v.performClick(); true
        }
    }

    private val clearFocus = Runnable { binding.overlayView.focusPoint = null }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        Log.e("CameraActivity", msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
}
