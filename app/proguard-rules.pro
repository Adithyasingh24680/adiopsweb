# ProCam13 ProGuard rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.adiopsweb.camera.** { *; }
