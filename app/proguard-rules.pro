# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.linbox.data.db.entity.** { *; }
# WebView JS 桥（blob 下载分块回调）
-keepclassmembers class com.linbox.apps.browser.BrowserEngine$* {
    @android.webkit.JavascriptInterface <methods>;
}
