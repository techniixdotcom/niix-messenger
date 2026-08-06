#!/usr/bin/env bash
#
# Builds a signed NiiX release APK on Linux with a self-contained toolchain.
# Installs JDK 17 (Temurin), Gradle 8.11.1, and the Android SDK (platform 35,
# build-tools 35.0.0) into <project>/.toolchain — no sudo, no system changes.
#
# A release build needs your own signing keystore first -- see keystore.properties.example
# for how to generate one. Without it, this script tells you what's missing and stops
# rather than producing an unsigned or debug-signed APK.
#
# Usage:
#   ./build-niix.sh [PROJECT_DIR_OR_ZIP]              # signed release build (needs keystore.properties)
#   ./build-niix.sh --debug [PROJECT_DIR_OR_ZIP]       # debug-signed build instead, no keystore needed
#   ./build-niix.sh --setup-only [PROJECT_DIR_OR_ZIP]
#   ./build-niix.sh --update [PROJECT_DIR_OR_ZIP]      # bump everything to newest STABLE, then build
#
# Optional overrides (export before running):
#   NIIX_TOOLCHAIN     install location (default: <project>/.toolchain)
#   JDK_URL            JDK 17 tarball URL
#   GRADLE_URL         Gradle 8.11.1 distribution URL
#   CMDLINE_TOOLS_URL  Android command-line tools zip URL

set -euo pipefail

GRADLE_VERSION="8.11.1"
ANDROID_PLATFORM="android-35"
ANDROID_BUILD_TOOLS="35.0.0"
DEFAULT_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_BASE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
ADOPTIUM_API="https://api.adoptium.net/v3/binary/latest/17/ga/linux"

SETUP_ONLY=0
UPDATE=0
DEBUG_BUILD=0
PROJECT_ARG=""

c_red=$'\033[31m'; c_grn=$'\033[32m'; c_ylw=$'\033[33m'; c_blu=$'\033[34m'; c_rst=$'\033[0m'
log()  { printf '%s==>%s %s\n' "$c_blu" "$c_rst" "$*"; }
ok()   { printf '%s ok %s %s\n' "$c_grn" "$c_rst" "$*"; }
warn() { printf '%swarn%s %s\n' "$c_ylw" "$c_rst" "$*" >&2; }
die()  { printf '%sERR %s %s\n' "$c_red" "$c_rst" "$*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

fetch() {
    local url="$1" out="$2"
    if have curl; then
        curl -fL --retry 3 --retry-delay 2 --connect-timeout 30 -o "$out" "$url"
    elif have wget; then
        wget -q --tries=3 --timeout=30 -O "$out" "$url"
    else
        die "Need curl or wget. Install with: sudo pacman -S --needed curl"
    fi
}

verify_zip() { unzip -tqq "$1" >/dev/null 2>&1 || die "Downloaded archive is corrupt or blocked: $1"; }
verify_tar() { tar -tzf "$1" >/dev/null 2>&1 || die "Downloaded archive is corrupt or blocked: $1"; }

parse_args() {
    for arg in "$@"; do
        case "$arg" in
            --setup-only) SETUP_ONLY=1 ;;
            --update) UPDATE=1 ;;
            --debug) DEBUG_BUILD=1 ;;
            -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
            *) PROJECT_ARG="$arg" ;;
        esac
    done
}

require_tools() {
    local missing=()
    have unzip || missing+=("unzip")
    have tar   || missing+=("tar")
    have curl || have wget || missing+=("curl")
    if [ "${#missing[@]}" -gt 0 ]; then
        die "Missing tools: ${missing[*]}. Install with: sudo pacman -S --needed ${missing[*]}"
    fi
}

detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64) JDK_ARCH="x64" ;;
        aarch64|arm64)
            JDK_ARCH="aarch64"
            warn "On arm64, the Android build-tools (aapt2) are primarily published for x86_64; if the build fails on aapt2, build on an x86_64 machine."
            ;;
        *) die "Unsupported CPU architecture: $(uname -m)" ;;
    esac
}

resolve_project_dir() {
    local candidate=""
    if [ -n "$PROJECT_ARG" ]; then
        if [ -f "$PROJECT_ARG" ] && [[ "$PROJECT_ARG" == *.zip ]]; then
            local dest="${PROJECT_ARG%.zip}-src"
            log "Unzipping $PROJECT_ARG -> $dest"
            rm -rf "$dest"; mkdir -p "$dest"
            unzip -q "$PROJECT_ARG" -d "$dest"
            candidate="$dest"
        elif [ -d "$PROJECT_ARG" ]; then
            candidate="$PROJECT_ARG"
        else
            die "Path not found: $PROJECT_ARG"
        fi
    fi
    if [ -z "$candidate" ]; then
        if [ -f "./settings.gradle.kts" ]; then candidate="."
        elif [ -f "$SCRIPT_DIR/settings.gradle.kts" ]; then candidate="$SCRIPT_DIR"
        elif [ -f "./niix/settings.gradle.kts" ]; then candidate="./niix"
        fi
    fi
    [ -n "$candidate" ] || die "Could not locate the project. Pass the project folder or niix.zip as an argument."
    # If settings.gradle.kts is one level down, descend into it.
    if [ ! -f "$candidate/settings.gradle.kts" ]; then
        local found
        found="$(find "$candidate" -maxdepth 2 -name settings.gradle.kts -print -quit 2>/dev/null || true)"
        [ -n "$found" ] || die "No settings.gradle.kts under: $candidate"
        candidate="$(dirname "$found")"
    fi
    PROJECT_DIR="$(cd "$candidate" && pwd -P)"
    [ -f "$PROJECT_DIR/app/build.gradle.kts" ] || die "This does not look like the NiiX project (no app/build.gradle.kts) at $PROJECT_DIR"
    ok "Project: $PROJECT_DIR"
}

install_jdk() {
    JAVA_HOME="$TOOLCHAIN/jdk17"
    if [ -x "$JAVA_HOME/bin/javac" ]; then ok "JDK 17 already present"; return; fi
    local url="${JDK_URL:-$ADOPTIUM_API/$JDK_ARCH/jdk/hotspot/normal/eclipse}"
    local tarball="$DL/jdk17.tar.gz"
    log "Downloading JDK 17 ($JDK_ARCH)"
    fetch "$url" "$tarball"
    verify_tar "$tarball"
    rm -rf "$JAVA_HOME"; mkdir -p "$JAVA_HOME"
    tar -xzf "$tarball" -C "$JAVA_HOME" --strip-components=1
    [ -x "$JAVA_HOME/bin/javac" ] || die "JDK extraction failed"
    ok "JDK 17 installed"
}

install_gradle() {
    GRADLE_BIN="$TOOLCHAIN/gradle-${GRADLE_VERSION}/bin/gradle"
    if [ -x "$GRADLE_BIN" ]; then ok "Gradle ${GRADLE_VERSION} already present"; return; fi
    local url="${GRADLE_URL:-$GRADLE_BASE_URL}"
    local zip="$DL/gradle.zip"
    log "Downloading Gradle ${GRADLE_VERSION}"
    fetch "$url" "$zip"
    # Verify against the official SHA-256 when reachable (best effort).
    local sums="$DL/gradle.sha256"
    if fetch "${url}.sha256" "$sums" 2>/dev/null && [ -s "$sums" ] && have sha256sum; then
        local want got
        want="$(tr -d '[:space:]' < "$sums")"
        got="$(sha256sum "$zip" | awk '{print $1}')"
        [ "$want" = "$got" ] || die "Gradle checksum mismatch (expected $want, got $got)"
        ok "Gradle checksum verified"
    else
        warn "Skipping Gradle checksum verification (offline or sha256sum missing)"
    fi
    verify_zip "$zip"
    unzip -q "$zip" -d "$TOOLCHAIN"
    [ -x "$GRADLE_BIN" ] || die "Gradle extraction failed"
    ok "Gradle ${GRADLE_VERSION} installed"
}

install_android_sdk() {
    ANDROID_SDK_ROOT="$TOOLCHAIN/android-sdk"
    local cli_dir="$ANDROID_SDK_ROOT/cmdline-tools/latest"
    SDKMANAGER="$cli_dir/bin/sdkmanager"
    if [ ! -x "$SDKMANAGER" ]; then
        local url="${CMDLINE_TOOLS_URL:-$DEFAULT_CMDLINE_TOOLS_URL}"
        local zip="$DL/cmdline-tools.zip"
        log "Downloading Android command-line tools"
        if ! fetch "$url" "$zip"; then
            die "Failed to download command-line tools. If Google rotated the URL, get the latest 'Command line tools only' link from https://developer.android.com/studio#command-line-tools-only and re-run with: CMDLINE_TOOLS_URL=<url> $0"
        fi
        verify_zip "$zip"
        # The zip extracts a top-level 'cmdline-tools/' dir; the SDK requires it
        # to live under cmdline-tools/latest/ for sdkmanager to resolve packages.
        local tmp="$DL/cmdline-tools-extract"
        rm -rf "$tmp"; mkdir -p "$tmp"
        unzip -q "$zip" -d "$tmp"
        rm -rf "$cli_dir"; mkdir -p "$(dirname "$cli_dir")"
        mv "$tmp/cmdline-tools" "$cli_dir"
        [ -x "$SDKMANAGER" ] || die "command-line tools extraction failed"
        ok "Command-line tools installed"
    else
        ok "Command-line tools already present"
    fi

    export JAVA_HOME ANDROID_SDK_ROOT
    export ANDROID_HOME="$ANDROID_SDK_ROOT"

    log "Accepting SDK licenses"
    # 'yes' receives SIGPIPE when sdkmanager exits; disable pipefail around it.
    set +o pipefail
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1
    set -o pipefail

    log "Installing platform-tools, $ANDROID_PLATFORM, build-tools;$ANDROID_BUILD_TOOLS"
    "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" \
        "platform-tools" \
        "platforms;$ANDROID_PLATFORM" \
        "build-tools;$ANDROID_BUILD_TOOLS" >/dev/null
    ok "Android SDK components installed"
}

write_project_config() {
    printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$PROJECT_DIR/local.properties"
    ok "Wrote local.properties"

    cat > "$TOOLCHAIN/env.sh" <<EOF
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export PATH="\$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$(dirname "$GRADLE_BIN"):\$PATH"
EOF
    ok "Wrote $TOOLCHAIN/env.sh (source it for manual builds)"
}

generate_wrapper() {
    # The shipped project omits gradle-wrapper.jar; materialize it so ./gradlew works later.
    if [ ! -f "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
        log "Generating Gradle wrapper jar"
        if JAVA_HOME="$JAVA_HOME" "$GRADLE_BIN" -p "$PROJECT_DIR" --no-daemon \
            wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin; then
            ok "Wrapper generated (./gradlew is now usable)"
        else
            warn "Could not generate the wrapper jar; building with the toolchain Gradle instead"
        fi
    fi
}

build_apk() {
    if [ "$DEBUG_BUILD" -eq 1 ]; then
        log "Building :app:assembleDebug (--debug requested; not for real use, no signing needed)"
        JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_SDK_ROOT" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
            "${GRADLE_CMD:-$GRADLE_BIN}" -p "$PROJECT_DIR" --no-daemon :app:assembleDebug

        local apk="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
        [ -f "$apk" ] || die "Build finished but APK not found at $apk"
        local size; size="$(du -h "$apk" | awk '{print $1}')"
        printf '\n%s================================================================%s\n' "$c_grn" "$c_rst"
        ok "Debug APK built: $apk ($size)"
        printf '\nInstall on a connected device (USB debugging on):\n  %s/platform-tools/adb install -r "%s"\n' "$ANDROID_SDK_ROOT" "$apk"
        printf '\nRebuild later without re-running setup:\n  source "%s/env.sh" && (cd "%s" && gradle :app:assembleDebug)\n' "$TOOLCHAIN" "$PROJECT_DIR"
        return 0
    fi

    if [ ! -f "$PROJECT_DIR/keystore.properties" ]; then
        die "No release keystore configured. Copy keystore.properties.example to keystore.properties in $PROJECT_DIR, fill in your own signing details (see the comments in that file for the exact 'keytool' command to generate a keystore), then re-run this script. To build an unsigned debug APK instead for quick testing, run with --debug."
    fi

    log "Building :app:assembleRelease (first run downloads dependencies; this can take a while)"
    JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_SDK_ROOT" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
        "${GRADLE_CMD:-$GRADLE_BIN}" -p "$PROJECT_DIR" --no-daemon :app:assembleRelease

    local apk="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
    [ -f "$apk" ] || die "Build finished but APK not found at $apk"
    local size; size="$(du -h "$apk" | awk '{print $1}')"
    printf '\n%s================================================================%s\n' "$c_grn" "$c_rst"
    ok "Signed release APK built: $apk ($size)"
    printf '\nInstall on a connected device (USB debugging on):\n  %s/platform-tools/adb install -r "%s"\n' "$ANDROID_SDK_ROOT" "$apk"
    printf '\nRebuild later without re-running setup:\n  source "%s/env.sh" && (cd "%s" && gradle :app:assembleRelease)\n' "$TOOLCHAIN" "$PROJECT_DIR"
}

update_versions() {
    log "Updating Gradle wrapper to the latest release"
    JAVA_HOME="$JAVA_HOME" "$GRADLE_BIN" -p "$PROJECT_DIR" --no-daemon \
        wrapper --gradle-version latest --distribution-type bin || warn "wrapper update failed"

    log "Updating Android SDK components to the latest available"
    set +o pipefail
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --update >/dev/null 2>&1
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1
    set -o pipefail
    "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "platform-tools" "platforms;$ANDROID_PLATFORM" "build-tools;$ANDROID_BUILD_TOOLS" >/dev/null 2>&1 || true

    log "Bumping dependency versions in gradle/libs.versions.toml to newest stable"
    if JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_SDK_ROOT" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
        "$GRADLE_BIN" -p "$PROJECT_DIR" --no-daemon \
        --init-script "$PROJECT_DIR/gradle/niix-update.init.gradle" versionCatalogUpdate; then
        ok "Version catalog updated to newest stable releases"
    else
        warn "Could not auto-update the version catalog; leaving versions unchanged"
    fi
    # After a version bump, build with the (possibly newer) wrapper Gradle so it
    # matches any bumped Android Gradle Plugin.
    GRADLE_CMD="$PROJECT_DIR/gradlew"
    warn "A major toolchain bump (e.g. AGP 8 -> 9) can require manual migration; if the build now fails, revert gradle/libs.versions.toml."
}

run() {
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
    parse_args "$@"
    require_tools
    detect_arch
    resolve_project_dir

    TOOLCHAIN="${NIIX_TOOLCHAIN:-$PROJECT_DIR/.toolchain}"
    mkdir -p "$TOOLCHAIN"
    TOOLCHAIN="$(cd "$TOOLCHAIN" && pwd -P)"
    DL="$TOOLCHAIN/downloads"; mkdir -p "$DL"
    log "Toolchain: $TOOLCHAIN"

    install_jdk
    install_gradle
    install_android_sdk
    write_project_config

    if [ "$SETUP_ONLY" -eq 1 ]; then
        generate_wrapper
        ok "Setup complete (--setup-only)."
        printf '\nBuild a signed release (needs keystore.properties -- see keystore.properties.example):\n  source "%s/env.sh" && (cd "%s" && gradle :app:assembleRelease)\n' "$TOOLCHAIN" "$PROJECT_DIR"
        printf '\nOr a debug build for quick testing:\n  source "%s/env.sh" && (cd "%s" && gradle :app:assembleDebug)\n' "$TOOLCHAIN" "$PROJECT_DIR"
        exit 0
    fi

    generate_wrapper
    if [ "$UPDATE" -eq 1 ]; then
        update_versions
    fi
    build_apk
}

# Stream everything to the terminal (in color) and to a plain-text log in real time,
# so a failing build (including Gradle's "e: ...File.kt:line: error:" lines) is captured
# for debugging. Watch it live from another terminal with: tail -f <log path>
LOG_FILE="${NIIX_LOG:-$PWD/build-niix.log}"
if ! : > "$LOG_FILE" 2>/dev/null; then
    LOG_FILE="${TMPDIR:-/tmp}/niix-build.log"
    : > "$LOG_FILE"
fi
printf '==> Logging to %s  (tail -f to watch live)\n' "$LOG_FILE"

if command -v sed >/dev/null 2>&1; then
    strip_ansi() { sed -u 's/\x1b\[[0-9;]*m//g'; }
else
    strip_ansi() { cat; }
fi

set +e
run "$@" 2>&1 | tee >(strip_ansi >> "$LOG_FILE")
rc=${PIPESTATUS[0]}
set -e
printf '==> Full log saved to %s\n' "$LOG_FILE"
exit "$rc"
