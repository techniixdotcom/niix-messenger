-keep class net.zetetic.database.** { *; }
# Called from native code by name.
-keep class com.lambdapioneer.argon2kt.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
