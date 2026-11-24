# JS Bridge 必须保留注解方法名，否则 WebView 找不到
-keepclassmembers class com.fred.pinyin.MainActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
