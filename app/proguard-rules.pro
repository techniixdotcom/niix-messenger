-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**

-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
