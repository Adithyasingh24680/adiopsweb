package com.adiopsweb.camera.camera

import android.hardware.camera2.CameraCharacteristics

/** Represents which physical rear lens is active */
enum class LensMode(
    val label: String,
    val zoomFactor: Float,
    val focalLengthMm: Int,
    val aperture: String,
    val sensorLabel: String
) {
    ULTRAWIDE(
        label = "0.6×",
        zoomFactor = 0.6f,
        focalLengthMm = 15,
        aperture = "f/2.0",
        sensorLabel = "Samsung JN5 · 15mm"
    ),
    WIDE(
        label = "1×",
        zoomFactor = 1.0f,
        focalLengthMm = 23,
        aperture = "f/1.6",
        sensorLabel = "Sony LYT-808 · 23mm"
    ),
    TELEPHOTO(
        label = "3×",
        zoomFactor = 3.0f,
        focalLengthMm = 73,
        aperture = "f/2.6",
        sensorLabel = "Sony LYT-600 · 73mm"
    )
}

/** Photo or video capture mode */
enum class CaptureMode { PHOTO, VIDEO, PORTRAIT, PRO }

/** Video resolution options */
enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    UHD_4K("4K", 3840, 2160),
    FHD_1080P("1080p", 1920, 1080),
    HD_720P("720p", 1280, 720)
}

/** Supported frame rates */
enum class FrameRate(val fps: Int, val label: String) {
    FPS_24(24, "24fps"),
    FPS_30(30, "30fps"),
    FPS_60(60, "60fps"),
    FPS_120(120, "120fps"),
    FPS_240(240, "240fps")
}

/** Color/film profiles */
enum class ColorProfile(val label: String) {
    NATURAL("Natural"),
    VIVID("Vivid"),
    FLAT("Flat"),
    LOG("Log"),
    FILM_NOIR("Film Noir"),
    WARM("Warm"),
    COOL("Cool"),
    FADE("Fade")
}

/** White balance presets */
enum class WhiteBalance(val label: String, val awbMode: Int) {
    AUTO("Auto", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_SHADE),
    TUNGSTEN("Tungsten", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescent", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    TWILIGHT("Twilight", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_TWILIGHT)
}

/** Focus mode */
enum class FocusMode(val label: String) {
    AUTO("AF"),
    CONTINUOUS("C-AF"),
    MANUAL("MF")
}

/** All manual settings bundled together */
data class ProSettings(
    val iso: Int = 0,            // 0 = auto
    val shutterUs: Long = 0L,    // 0 = auto, otherwise microseconds
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val manualFocusDistance: Float = 0f,  // 0..1, 0=infinity
    val exposureCompensation: Int = 0     // in steps
)

/** Grid overlay types */
enum class GridType(val label: String) {
    OFF("Off"),
    THIRDS("3×3"),
    GOLDEN("Golden"),
    SQUARE("9×9")
}


val SHUTTER_SPEEDS = listOf(
    Pair("Auto", 0L),
    Pair("1/4000", 250L),
    Pair("1/2000", 500L),
    Pair("1/1000", 1_000L),
    Pair("1/500", 2_000L),
    Pair("1/250", 4_000L),
    Pair("1/125", 8_000L),
    Pair("1/60", 16_667L),
    Pair("1/30", 33_333L),
    Pair("1/15", 66_667L),
    Pair("1/8", 125_000L),
    Pair("1/4", 250_000L),
    Pair("1/2", 500_000L),
    Pair("1\"", 1_000_000L),
    Pair("2\"", 2_000_000L),
    Pair("4\"", 4_000_000L)
)

/** ISO options */
val ISO_VALUES = listOf(0, 50, 100, 200, 400, 800, 1600, 3200, 6400)
