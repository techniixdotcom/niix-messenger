import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The signing key lives outside the project (build-niix.sh keeps it in ~/.niix-keys and passes the
// path in NIIX_KEYSTORE_PROPERTIES), so a zip or upload of the project can never carry it along.
// A keystore.properties in the project root still works for anyone building it their own way.
val keystorePropertiesFile = System.getenv("NIIX_KEYSTORE_PROPERTIES")
    ?.let { file(it) }
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties()

val forceUnsigned = (project.findProperty("niixUnsigned") as String?)?.toBoolean() == true
val hasReleaseKeystore = !forceUnsigned && keystorePropertiesFile.exists()

// Whether this run packages a release APK or bundle. The release variant is configured on every
// run, including test and assembleDebug, and those shouldn't need the signing key.
val releaseArtifactRequested = gradle.startParameter.taskNames.any { requested ->
    val task = requested.substringAfterLast(':')
    task in setOf("assemble", "build", "bundle") ||
        listOf("assembleRelease", "bundleRelease", "packageRelease", "installRelease")
            .any { task.startsWith(it, ignoreCase = true) }
}
if (hasReleaseKeystore) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

// The public halves of the keys whose signature the app accepts on an update: the one releases are
// signed with, then a standby kept offline that takes over if the first is lost or leaks. Public,
// so they live in the repository; the private halves stay with whoever publishes releases.
val updatePublicKeys = rootProject.file("update-key.pub").readLines().map(String::trim).filter(String::isNotEmpty)
require(updatePublicKeys.size in 1..2 && updatePublicKeys.all { Regex("[0-9a-f]{64}").matches(it) }) {
    "update-key.pub must hold one or two Ed25519 public keys, one per line, as 64 lowercase hex characters"
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
    compileSdk = 36

    defaultConfig {
        applicationId = "app.niix"
        // Android 10, for scoped storage. Saving to Downloads below this needs
        // WRITE_EXTERNAL_STORAGE, which grants access to all shared storage. That's a worse trade
        // for this app than dropping releases long past their last security update.
        minSdk = 29
        // Android 16, which Google Play requires for updates from 31 August 2026. Back handling
        // goes through the OnBackPressedDispatcher everywhere and edge-to-edge was already handled,
        // so the behaviour changes that come with it need nothing more.
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        buildConfigField("String", "UPDATE_PUBLIC_KEYS", "\"${updatePublicKeys.joinToString(",")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }

        // Only the languages NiiX is translated into. Libraries ship their own strings in dozens
        // more, and without this a phone in, say, Dutch would show NiiX in English with the odd
        // library dialog in Dutch.
        resourceConfigurations += listOf("en", "de", "fr", "es", "it", "ru", "fa", "zh", "ar", "ja", "th", "ko")
    }

    androidResources {
        // Lists those languages for Android 13+'s per-app language setting, so someone can run NiiX
        // in a different language from the rest of the phone. Needs res/resources.properties.
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
    }

    // Two builds from the same code, differing only in how the app gets updated.
    //
    // github: published on GitHub Releases and updates itself, checking signed releases and
    //         installing them through Android's package installer.
    // play:   published on Google Play, which doesn't allow an app to update itself. This build
    //         has no updater: the install permission is removed from the manifest
    //         (app/src/play/AndroidManifest.xml) and SELF_UPDATE switches off the update settings
    //         and checks.
    //
    // Both keep the same application id, so a phone has one or the other, never both.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                // build-niix.sh asks for the password and passes it in the environment, so it
                // never has to be written to disk. A password in keystore.properties still works.
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("NIIX_KEY_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("NIIX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false

            // R8 strips unused code and resources: in-app updates download over Tor, where every
            // megabyte counts, and less code is less attack surface. The obfuscation that comes
            // with it isn't a security measure.
            //
            // The keep rules in proguard-rules.pro cover the libraries that look classes up by name
            // at runtime (libsignal and SQLCipher over JNI, Tink, kmp-tor, ZXing), where shrinking
            // would otherwise break things at runtime instead of build time.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else if (!forceUnsigned && releaseArtifactRequested) {
                // Fail rather than quietly produce an unsigned release APK: it looks like a normal
                // build until someone installs it, and anyone can sign it later. Unsigned builds
                // are still possible on purpose with -PniixUnsigned=true, which the
                // reproducible-build check uses.
                throw GradleException(
                    "Release build requested but no signing keystore was found. Create one with " +
                        "./build-niix.sh --create-keys, or pass " +
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
    implementation(project(":core:nostr"))
    implementation(project(":core:messaging"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.swiperefreshlayout)
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
