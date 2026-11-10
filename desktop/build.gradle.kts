import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm.desktop)
    alias(libs.plugins.flatpak.gradle.generator)
    application
}

val versionProperties = Properties().apply {
    rootDir.resolve("../version.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
version = (findProperty("versionName") as String?)
    ?: System.getenv("NIIX_VERSION_NAME")
    ?: versionProperties.getProperty("versionName")
    ?: "0.1.0"

val core: File = rootDir.resolve("../core").canonicalFile

// The core modules' own sources, compiled once more for the desktop. What in them is Android-only
// is swapped out: SQLCipher for Android, ContentValues and friends come from src/compat (the same
// API over SQLite3 Multiple Ciphers), and the six storage files that use the Android keystore or
// a Context have desktop versions in src/storage.
val androidOnlyStorageFiles = listOf(
    "SecureStorage.kt",
    "SecureDatabase.kt",
    "DatabaseSecretProvider.kt",
    "KeystoreKeyManager.kt",
    "LockedInboxQueue.kt",
    "PassphraseKdf.kt",
)

kotlin {
    jvmToolchain(25)
    sourceSets {
        named("main") {
            kotlin.srcDirs(
                listOf("model", "transport", "nostr", "crypto", "messaging", "storage")
                    .map { core.resolve("$it/src/main/java") } +
                    listOf(file("src/main/kotlin"), file("src/storage/kotlin")),
            )
            kotlin.exclude(androidOnlyStorageFiles.map { "app/niix/core/storage/$it" })
        }
    }
}

sourceSets {
    main {
        java.srcDir("src/compat/java")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javagi.adw)
    implementation(libs.javagi.secret)
    implementation(libs.javagi.gstreamer)
    implementation(libs.sqlite.jdbc.crypt)
    implementation(libs.libsignal.client)
    implementation(libs.tink.java)
    implementation(libs.bouncycastle)
    implementation(libs.zxing.core)
    implementation(libs.kmp.tor.runtime)
    implementation(libs.kmp.tor.resource.exec.tor)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("app.niix.desktop.MainKt")
    applicationName = "niix"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+UseSerialGC",
        "-Xss2m",
        "-Dfile.encoding=UTF-8",
    )
}

tasks.processResources {
    // The phone app's translations, reused as they are: the desktop looks its text up by the same
    // names, so everything both apps say is translated wherever the phone app is.
    from(rootDir.resolve("../app/src/main/res")) {
        include("values/strings.xml", "values-*/strings.xml")
        into("android-strings")
    }
    from(rootDir.resolve("../app/src/main/res/font")) {
        include("*.ttf")
        into("fonts")
    }
    val versionName = project.version.toString()
    inputs.property("versionName", versionName)
    filesMatching("app/niix/desktop/build.properties") {
        expand("versionName" to versionName)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnit()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    environment("NIIX_NO_KEYRING", "1")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "NiiX",
            "Implementation-Version" to project.version,
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

// Writes flatpak/gradle-sources.json: every file the build downloads, with its hash, so the Flathub
// build can fetch them up front and then run without a network. Run from BUILD.sh
// (--flathub-sources) after any dependency change.
tasks.flatpakGradleGenerator {
    outputFile.set(file("flatpak/gradle-sources.json"))
    // Relative to the repository root, where flatpak-builder unpacks the sources; the build then
    // finds it as desktop/offline-repository (see settings.gradle.kts).
    downloadDirectory.set("desktop/offline-repository")
    excludeConfigurations.set(setOf("testCompileClasspath", "testRuntimeClasspath"))
}
