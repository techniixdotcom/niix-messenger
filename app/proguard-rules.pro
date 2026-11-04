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

# kmp-process (used by kmp-tor to launch the tor binary) has a desktop-JVM code path that reads
# the process id via java.lang.management. Those classes do not exist on Android, and that path
# is never taken there -- it checks for them at runtime and falls back. R8 still objects to the
# unresolved reference, so it is silenced rather than kept: keeping it is impossible, since the
# classes genuinely aren't present on the platform.
-dontwarn java.lang.management.**

-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-dontwarn androidx.exifinterface.**

-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
