-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**

-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

-keep class io.matthewnelson.kmp.tor.** { *; }
-keepclassmembers class io.matthewnelson.kmp.tor.** { *; }
-dontwarn io.matthewnelson.kmp.tor.**
-keep class io.matthewnelson.kmp.file.** { *; }
-dontwarn io.matthewnelson.kmp.file.**
-keep class io.matthewnelson.kmp.process.** { *; }
-dontwarn io.matthewnelson.kmp.process.**

# kmp-process (used by kmp-tor to start tor) has a desktop-only path that reads the pid through
# java.lang.management. Android doesn't have those classes and the path is never taken there, so
# just silence R8 about them.
-dontwarn java.lang.management.**

-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-dontwarn androidx.exifinterface.**

-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
