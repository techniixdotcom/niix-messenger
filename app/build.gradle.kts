import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing comes from a keystore.properties file (never from a value baked into this
// file or committed to the repo) -- see keystore.properties.example for the format.
//
// Its location: NIIX_KEYSTORE_PROPERTIES env var if set (an absolute path -- this is how you
// keep your signing key on removable media, e.g. a USB drive, entirely outside this project
// checkout, and build the same signed APK from any machine by pointing at it -- see
// build-niix.sh, which resolves this automatically), otherwise the local, git-ignored
// keystore.properties right here in the project root.
val keystorePropertiesFile = System.getenv("NIIX_KEYSTORE_PROPERTIES")
    ?.let { file(it) }
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

// Version can be set per-build without editing this file:
//   ./gradlew assembleRelease -PversionName=1.2.0 -PversionCode=5
// or via environment variables (handy in CI): NIIX_VERSION_NAME / NIIX_VERSION_CODE.
// Falls back to the defaults below when neither is given.
val versionNameInput = (project.findProperty("versionName") as String?) ?: System.getenv("NIIX_VERSION_NAME")
val versionCodeInput = (project.findProperty("versionCode") as String?) ?: System.getenv("NIIX_VERSION_CODE")
val resolvedVersionCode = versionCodeInput?.let {
    it.toIntOrNull() ?: throw GradleException("Invalid versionCode '$it' -- must be a whole number")
} ?: 1

android {
    namespace = "app.niix"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.niix"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = versionNameInput ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Real phones only; x86/x86_64 are emulator-only and roughly double the size.
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
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
            // Minification/shrinking is left OFF for now: this app has never had a minified
            // build tested on a device, and R8 stripping something libsignal/SQLCipher/kmp-tor
            // reach via JNI or reflection would fail silently at runtime, not at build time --
            // exactly the kind of bug that's very hard to catch without a real phone. Keep rules
            // for all three are already in proguard-rules.pro, ready for when minification is
            // deliberately tried and verified on-device as its own step.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
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
            // kmp-tor ships the tor binary as a native lib; it must be unpacked to the
            // app's nativeLibraryDir on install so it can be executed.
            useLegacyPackaging = true
            // Test-only native lib shipped inside libsignal; never needed at runtime.
            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            excludes += setOf(
                "libsignal_jni*.dylib",
                "signal_jni*.dll",
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                // bcprov-jdk18on and jspecify both ship an OSGi manifest at this exact path;
                // neither is used at runtime (no OSGi container here), so it's safe to drop
                // rather than pick one arbitrarily.
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
