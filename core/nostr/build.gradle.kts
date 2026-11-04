plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.niix.core.nostr"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":core:model"))
    // The public API takes a KeyValueStore and a TorTransport.
    api(project(":core:storage"))
    api(project(":core:transport"))
    implementation(libs.kotlinx.coroutines.android)
    // secp256k1 for the BIP-340 signatures relays require. Storage and the app already depend on
    // it.
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
