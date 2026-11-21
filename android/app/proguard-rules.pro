# Add project specific ProGuard rules here.
# react-native-reanimated
-keep class com.swmansion.reanimated.** { *; }
-keep class com.facebook.react.turbomodule.** { *; }

# Keep all WebView JavascriptInterfaces and Foreground Services
-keep class com.sisbom.misisbom.MainActivity$WebAppInterface { *; }
-keep class com.sisbom.misisbom.MainActivity$SentinelAndroidBridge { *; }
-keep class com.sisbom.misisbom.NavGpsTrackingService { *; }
-keep class com.sisbom.misisbom.NavGpsFixData { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface, *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
