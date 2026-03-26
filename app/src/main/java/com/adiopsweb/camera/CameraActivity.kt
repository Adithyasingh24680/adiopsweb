package com.adiopsweb.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.provider.MediaStore
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.adiopsweb.camera.camera.*
import com.adiopsweb.camera.databinding.ActivityCameraBinding
import com.google.android.material.snackbar.Snackbar
import kotlin.math.sqrt

class CameraActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraHandler: Camera2Handler
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Current settings state
    private var captureMode = CaptureMode.PHOTO
    private var currentLens = LensMode.WIDE
    private var videoResolution = VideoResolution.FHD_1080P
    private var frameRate = FrameRate.FPS_30
    private var proSettings = ProSettings()
    private var colorProfile = ColorProfile.NATURAL
    private var isFlashOn = false
    private var showGrid = false
    private var isHdrOn = false
    private var isRawOn = false

    // Zoom gesture
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var currentZoom = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full screen immersive
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        initCamera()
        initSensors()
        setupUI()
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    private fun hasPermissions(): Boolean {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
            initCamera(); initSensors(); setupUI()
        } else {
            Toast.makeText(this, "Camera & audio permissions required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ── Camera Init ─────────────────────────────────────────────────────────

    private fun initCamera() {
        cameraHandler = Camera2Handler(
            context = this,
            textureView = binding.textureView,
            onError = { msg -> runOnUiThread { showError(msg) } },
            onPhotoSaved = { name -> runOnUiThread { showSnack("Saved: $name") } },
            onVideoStateChanged = { recording -> runOnUiThread { onRecordingStateChanged(recording) } }
        )

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                cameraHandler.openCamera(currentLens)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    // ── Sensors (gyro for level) ─────────────────────────────────────────────

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val angle = Math.toDegrees(Math.atan2(x.toDouble(), y.toDouble())).toFloat()
            binding.overlayView.levelAngle = angle
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUI() {
        setupLensButtons()
        setupModeSelector()
        setupCaptureButton()
        setupTopControls()
        setupGalleryButton()
        setupProPanel()
        setupVideoControls()
        setupPinchZoom()
        setupTapToFocus()
    }

    private fun setupGalleryButton() {
        binding.ivGallery.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            startActivity(intent)
        }
    }

    /** Lens selector: UW / 1x / 3x */
    private fun setupLensButtons() {
        binding.btnLensUltrawide.setOnClickListener { switchLens(LensMode.ULTRAWIDE) }
        binding.btnLensWide.setOnClickListener { switchLens(LensMode.WIDE) }
        binding.btnLensTele.setOnClickListener { switchLens(LensMode.TELEPHOTO) }
        updateLensButtons()
    }

    private fun switchLens(lens: LensMode) {
        currentLens = lens
        currentZoom = 1.0f
        cameraHandler.setLens(lens)
        cameraHandler.setDigitalZoom(1.0f)
        binding.overlayView.currentLens = lens
        updateLensButtons()
        showSnack("${lens.focalLengthMm}mm · ${lens.sensorLabel}")
    }

    private fun updateLensButtons() {
        val orange = 0xFFFF8000.toInt()
        val dim = 0x88FFFFFF.toInt()
        listOf(
            binding.btnLensUltrawide to LensMode.ULTRAWIDE,
            binding.btnLensWide to LensMode.WIDE,
            binding.btnLensTele to LensMode.TELEPHOTO
        ).forEach { (btn, lens) ->
            val active = lens == currentLens
            btn.setTextColor(if (active) orange else dim)
            btn.textSize = if (active) 14f else 12f
        }
    }

    /** Mode selector: Photo / Video / Portrait / Pro */
    private fun setupModeSelector() {
        binding.tabPhoto.setOnClickListener { setMode(CaptureMode.PHOTO) }
        binding.tabVideo.setOnClickListener { setMode(CaptureMode.VIDEO) }
        binding.tabPortrait.setOnClickListener { setMode(CaptureMode.PORTRAIT) }
        binding.tabPro.setOnClickListener { setMode(CaptureMode.PRO) }
        setMode(CaptureMode.PHOTO)
    }

    private fun setMode(mode: CaptureMode) {
        captureMode = mode
        cameraHandler.setCaptureMode(mode)

        // Show/hide relevant panels
        binding.proPanel.isVisible = (mode == CaptureMode.PRO)
        binding.videoControlsPanel.isVisible = (mode == CaptureMode.VIDEO)
        binding.portraitPanel.isVisible = (mode == CaptureMode.PORTRAIT)

        // Update tab appearances
        val orange = 0xFFFF8000.toInt()
        val dim = 0x55FFFFFF.toInt()
        listOf(
            binding.tabPhoto to CaptureMode.PHOTO,
            binding.tabVideo to CaptureMode.VIDEO,
            binding.tabPortrait to CaptureMode.PORTRAIT,
            binding.tabPro to CaptureMode.PRO
        ).forEach { (tab, m) ->
            tab.setTextColor(if (m == mode) orange else dim)
        }

        updateCaptureButton()
    }

    private fun updateCaptureButton() {
        binding.btnCapture.setImageResource(
            when (captureMode) {
                CaptureMode.VIDEO -> R.drawable.ic_record
                else -> R.drawable.ic_shutter
            }
        )
    }

    /** Main capture / record button */
    private fun setupCaptureButton() {
        binding.btnCapture.setOnClickListener {
            when (captureMode) {
                CaptureMode.VIDEO -> {
                    if (cameraHandler.isCurrentlyRecording()) {
                        cameraHandler.stopVideoRecording()
                    } else {
                        cameraHandler.startVideoRecording()
                    }
                }
                else -> {
                    cameraHandler.capturePhoto()
                    animateCapture()
                }
            }
        }
    }

    private fun animateCapture() {
        binding.captureFlash.visibility = View.VISIBLE
        binding.captureFlash.animate().alpha(0f).setDuration(200).withEndAction {
            binding.captureFlash.visibility = View.GONE
            binding.captureFlash.alpha = 0.8f
        }.start()
    }

    private fun onRecordingStateChanged(recording: Boolean) {
        binding.btnCapture.setImageResource(
            if (recording) R.drawable.ic_stop
            else R.drawable.ic_record
        )
        binding.recordingIndicator.isVisible = recording
    }

    /** Top bar: flash, HDR, RAW, grid, timer, settings */
    private fun setupTopControls() {
        binding.btnFlash.setOnClickListener {
            isFlashOn = !isFlashOn
            cameraHandler.setFlash(isFlashOn)
            binding.btnFlash.alpha = if (isFlashOn) 1.0f else 0.6f
        }

        binding.btnHdr.setOnClickListener {
            isHdrOn = !isHdrOn
            cameraHandler.setHdr(isHdrOn)
            binding.btnHdr.alpha = if (isHdrOn) 1.0f else 0.6f
            binding.btnHdr.text = if (isHdrOn) "HDR" else "HDR"
        }

        binding.btnGrid.setOnClickListener {
            showGrid = !showGrid
            binding.overlayView.showGrid = showGrid
            binding.btnGrid.alpha = if (showGrid) 1.0f else 0.6f
        }

        binding.btnRaw.setOnClickListener {
            isRawOn = !isRawOn
            cameraHandler.setRaw(isRawOn)
            binding.btnRaw.alpha = if (isRawOn) 1.0f else 0.6f
            binding.btnRaw.text = if (isRawOn) "RAW" else "JPG"
        }

        binding.btnMenu.setOnClickListener {
            // Cycle through color profiles as a quick settings shortcut
            val profiles = ColorProfile.values()
            val next = profiles[(colorProfile.ordinal + 1) % profiles.size]
            colorProfile = next
            cameraHandler.setColorProfile(colorProfile)
            showSnack("Profile: ${colorProfile.label}")
        }

        binding.btnFlipCamera.setOnClickListener {
            showSnack("Front camera not supported in Pro mode")
        }
    }

    /** Pro mode panel: ISO, Shutter, WB, Focus, EV */
    private fun setupProPanel() {
        // ISO Spinner
        val isoAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            ISO_VALUES.map { if (it == 0) "Auto" else "ISO $it" })
        isoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerIso.adapter = isoAdapter
        binding.spinnerIso.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                proSettings = proSettings.copy(iso = ISO_VALUES[pos])
                cameraHandler.setProSettings(proSettings)
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        // Shutter Speed Spinner
        val shutterAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            SHUTTER_SPEEDS.map { it.first })
        shutterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerShutter.adapter = shutterAdapter
        binding.spinnerShutter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                proSettings = proSettings.copy(shutterUs = SHUTTER_SPEEDS[pos].second)
                cameraHandler.setProSettings(proSettings)
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        // White Balance Spinner
        val wbAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            WhiteBalance.values().map { it.label })
        wbAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerWb.adapter = wbAdapter
        binding.spinnerWb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                proSettings = proSettings.copy(whiteBalance = WhiteBalance.values()[pos])
                cameraHandler.setProSettings(proSettings)
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        // Focus Mode Spinner
        val focusAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            FocusMode.values().map { it.label })
        focusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFocus.adapter = focusAdapter
        binding.spinnerFocus.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val mode = FocusMode.values()[pos]
                proSettings = proSettings.copy(focusMode = mode)
                binding.seekbarMf.isVisible = (mode == FocusMode.MANUAL)
                cameraHandler.setProSettings(proSettings)
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        // Manual Focus SeekBar
        binding.seekbarMf.max = 100
        binding.seekbarMf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val dist = progress / 100f
                proSettings = proSettings.copy(manualFocusDistance = dist)
                cameraHandler.setProSettings(proSettings)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // EV SeekBar (-3 to +3 in steps)
        binding.seekbarEv.min = -6
        binding.seekbarEv.max = 6
        binding.seekbarEv.progress = 0
        binding.seekbarEv.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvEvValue.text = if (progress >= 0) "+$progress" else "$progress"
                proSettings = proSettings.copy(exposureCompensation = progress)
                cameraHandler.setProSettings(proSettings)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Color Profile Spinner
        val profileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            ColorProfile.values().map { it.label })
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerColorProfile.adapter = profileAdapter
        binding.spinnerColorProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                colorProfile = ColorProfile.values()[pos]
                cameraHandler.setColorProfile(colorProfile)
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    /** Video controls: resolution + frame rate via inline segmented buttons */
    private fun setupVideoControls() {
        // Resolution buttons
        binding.btnRes720.setOnClickListener { setVideoResolution(VideoResolution.HD_720P) }
        binding.btnRes1080.setOnClickListener { setVideoResolution(VideoResolution.FHD_1080P) }
        binding.btnRes4k.setOnClickListener { setVideoResolution(VideoResolution.UHD_4K) }

        // FPS buttons
        binding.btnFps30.setOnClickListener { setFrameRate(FrameRate.FPS_30) }
        binding.btnFps60.setOnClickListener { setFrameRate(FrameRate.FPS_60) }
        binding.btnFps120.setOnClickListener { setFrameRate(FrameRate.FPS_120) }

        // Apply initial state
        updateResolutionButtons()
        updateFpsButtons()
    }

    private fun setVideoResolution(res: VideoResolution) {
        videoResolution = res
        cameraHandler.setVideoResolution(res)
        updateResolutionButtons()
    }

    private fun setFrameRate(fps: FrameRate) {
        frameRate = fps
        cameraHandler.setFrameRate(fps)
        updateFpsButtons()
    }

    private fun updateResolutionButtons() {
        val orange = 0xFFFF8000.toInt()
        val dim = 0x88FFFFFF.toInt()
        binding.btnRes720.setTextColor(if (videoResolution == VideoResolution.HD_720P) orange else dim)
        binding.btnRes1080.setTextColor(if (videoResolution == VideoResolution.FHD_1080P) orange else dim)
        binding.btnRes4k.setTextColor(if (videoResolution == VideoResolution.UHD_4K) orange else dim)
    }

    private fun updateFpsButtons() {
        val orange = 0xFFFF8000.toInt()
        val dim = 0x88FFFFFF.toInt()
        binding.btnFps30.setTextColor(if (frameRate == FrameRate.FPS_30) orange else dim)
        binding.btnFps60.setTextColor(if (frameRate == FrameRate.FPS_60) orange else dim)
        binding.btnFps120.setTextColor(if (frameRate == FrameRate.FPS_120) orange else dim)
    }

    /** Pinch-to-zoom */
    private fun setupPinchZoom() {
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentZoom *= detector.scaleFactor
                    currentZoom = currentZoom.coerceIn(1.0f, 10.0f)
                    cameraHandler.setDigitalZoom(currentZoom)
                    binding.tvZoomLevel.text = String.format("%.1f×", currentZoom)
                    binding.tvZoomLevel.visibility = View.VISIBLE
                    binding.tvZoomLevel.removeCallbacks(hideZoomLabel)
                    binding.tvZoomLevel.postDelayed(hideZoomLabel, 2000)
                    return true
                }
            })

        binding.textureView.setOnTouchListener { v, event ->
            scaleGestureDetector?.onTouchEvent(event)
            v.performClick()
            false
        }
    }

    private val hideZoomLabel = Runnable {
        binding.tvZoomLevel.visibility = View.GONE
    }

    /** Tap-to-focus */
    private fun setupTapToFocus() {
        binding.textureView.setOnClickListener { view ->
            // Already handled inside onTouchListener for scale;
            // we use a GestureDetector for single taps here
        }

        val gestureDetector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val normX = e.x / binding.textureView.width
                    val normY = e.y / binding.textureView.height
                    cameraHandler.triggerTapToFocus(normX, normY)
                    binding.overlayView.focusPoint = PointF(e.x, e.y)
                    binding.overlayView.focusLocked = false
                    // Clear after 2s
                    binding.overlayView.removeCallbacks(clearFocusRing)
                    binding.overlayView.postDelayed(clearFocusRing, 2000)
                    return true
                }
            })

        binding.textureView.setOnTouchListener { v, event ->
            scaleGestureDetector?.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            v.performClick()
            true
        }
    }

    private val clearFocusRing = Runnable {
        binding.overlayView.focusPoint = null
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        cameraHandler.startBackgroundThread()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (binding.textureView.isAvailable) {
            cameraHandler.openCamera(currentLens)
        }
    }

    override fun onPause() {
        cameraHandler.closeCamera()
        cameraHandler.stopBackgroundThread()
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        Log.e("CameraActivity", msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showSnack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }
}
