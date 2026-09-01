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

-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

-dontwarn androidx.exifinterface.**

-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
