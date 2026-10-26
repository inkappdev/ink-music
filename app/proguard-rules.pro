# ink music's R8 rules (the release build shrinks and obfuscates; the defaults cover Android itself)

# NewPipe Extractor (the full build's YouTube Music) reads YouTube's pages with Rhino and jsoup and
# finds parts of itself by name: kept whole, as NewPipe's own app keeps it
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.jsoup.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**

# the poToken WebView calls back into Kotlin by name
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
