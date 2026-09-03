import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropertiesFile = System.getenv("NIIX_KEYSTORE_PROPERTIES")
    ?.let { file(it) }
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties()

val forceUnsigned = (project.findProperty("niixUnsigned") as String?)?.toBoolean() == true
val hasReleaseKeystore = !forceUnsigned && keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties()
if (versionPropertiesFile.exists()) {
    FileInputStream(versionPropertiesFile).use { versionProperties.load(it) }
}

val versionNameInput = (project.findProperty("versionName") as String?)
    ?: System.getenv("NIIX_VERSION_NAME")
    ?: versionProperties.getProperty("versionName")
val versionCodeInput = (project.findProperty("versionCode") as String?)
    ?: System.getenv("NIIX_VERSION_CODE")
    ?: versionProperties.getProperty("versionCode")
val resolvedVersionCode = versionCodeInput?.let {
    it.toIntOrNull() ?: throw GradleException("Invalid versionCode '$it' -- must be a whole number")
} ?: 1
val resolvedVersionName = versionNameInput ?: "0.1.0"

android {
    namespace = "app.niix"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.niix"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {

            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false

            // R8 strips unreachable code and resources. Two reasons this matters here beyond
            // tidiness: the APK is downloaded over Tor for in-app updates, where every megabyte
            // is real transfer time on a slow, high-latency link; and less shipped code is less
            // attack surface. Obfuscation is a side effect rather than the point -- it is not a
            // security control, since anything an attacker needs is recoverable from a binary
            // regardless.
            //
            // The keep rules in proguard-rules.pro cover everything that resolves classes by
            // name at runtime -- libsignal and SQLCipher (JNI), Tink, kmp-tor, BouncyCastle,
            // ZXing. Those are exactly the libraries where shrinking would otherwise remove
            // something the native layer looks up and fail at runtime rather than at build time.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else if (!forceUnsigned) {
                // Fail rather than quietly emitting an unsigned release APK. An unsigned build
                // looks identical to a signed one until someone tries to install it, and worse,
                // it can be signed later by anyone -- so a missing keystore silently producing
                // one is a supply-chain hazard, not a convenience. Deliberately unsigned builds
                // are still available via -PniixUnsigned=true, which is what the reproducible
                // build check uses; this only catches the accidental case.
                throw GradleException(
                    "Release build requested but no signing keystore was found. Create " +
                        "keystore.properties (or set NIIX_KEYSTORE_PROPERTIES), or pass " +
                        "-PniixUnsigned=true if you genuinely want an unsigned APK."
                )
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {

            useLegacyPackaging = true

            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            excludes += setOf(
                "libsignal_jni*.dylib",
                "signal_jni*.dll",
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",

                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:model"))
    implementation(project(":core:storage"))
    implementation(project(":core:crypto"))
    implementation(project(":core:transport"))
    implementation(project(":core:relay"))
    implementation(project(":core:messaging"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.kmp.tor.runtime)
    implementation(libs.kmp.tor.resource.exec.tor)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.exifinterface)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
