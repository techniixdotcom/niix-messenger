// The Linux desktop app, as a build of its own: the Android build needs the Android SDK just to be
// configured, and the Flatpak builds this one in a sandbox where there is none. It shares the
// version catalog and the core sources with the Android app.
//
// -PniixOffline=true resolves everything from offline-repository/ only, which is how the Flatpak
// builds it: flatpak-builder downloads every file listed in flatpak/gradle-sources.json there first,
// and the build itself has no network.

val offline = providers.gradleProperty("niixOffline").map(String::toBoolean).getOrElse(false)
val offlineRepository = file("offline-repository")

pluginManagement {
    val offlinePlugins = providers.gradleProperty("niixOffline").map(String::toBoolean).getOrElse(false)
    repositories {
        if (offlinePlugins) {
            maven { url = uri(file("offline-repository")) }
        } else {
            gradlePluginPortal()
            mavenCentral()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (offline) {
            maven { url = uri(offlineRepository) }
        } else {
            mavenCentral()
            maven {
                name = "SignalBuildArtifacts"
                url = uri("https://build-artifacts.signal.org/libraries/maven/")
                content {
                    includeGroup("org.signal")
                }
            }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "niix-desktop"
