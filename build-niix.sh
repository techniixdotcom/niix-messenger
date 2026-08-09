#!/usr/bin/env bash
#
# Builds a signed NiiX release APK on Linux with a self-contained toolchain.
# Installs JDK 17 (Temurin), Gradle 8.11.1, and the Android SDK (platform 35,
# build-tools 35.0.0) into <project>/.toolchain — no sudo, no system changes.
#
# A release build needs a signing keystore. This script gets one ready automatically (ported
# from setup-keystore.sh, no separate script to run): everything security-sensitive -- the
# Android release keystore and the update-checker's Ed25519 signing key -- lives in one signing
# directory outside this project checkout (default $HOME/Documents/niix-signing, or
# --signing-dir / NIIX_SIGNING_DIR), which you can point at removable media so the same key
# material follows you between machines. If nothing's there yet, this generates a new keystore
# (prompting for a password) directly in that directory. Skipped entirely for --debug builds.
#
# Usage:
#   ./build-niix.sh [PROJECT_DIR_OR_ZIP]              # signed release build (needs keystore.properties)
#   ./build-niix.sh --debug [PROJECT_DIR_OR_ZIP]       # debug-signed build instead, no keystore needed
#   ./build-niix.sh --setup-only [PROJECT_DIR_OR_ZIP]
#   ./build-niix.sh --update [PROJECT_DIR_OR_ZIP]      # bump everything to newest STABLE, then build
#   ./build-niix.sh --version=1.3.6b [PROJECT_DIR_OR_ZIP]     # skip the interactive version prompt
#   ./build-niix.sh --update-key=PATH [PROJECT_DIR_OR_ZIP]    # also sign for the in-app update checker
#   ./build-niix.sh --update-key=PATH --publish-release [PROJECT_DIR_OR_ZIP]   # ...and publish to GitHub
#   ./build-niix.sh --signing-dir=DIR [PROJECT_DIR_OR_ZIP]   # non-default signing directory
#
# By default, an interactive terminal is asked for the version to put on this APK (versionName,
# the human-readable one shown in Settings/app stores -- any text works, e.g. 0.2.0 or "1.3.6 b"),
# suggesting whatever's currently in app/build.gradle.kts. Skip the prompt with --version=TEXT, or
# the NIIX_VERSION_NAME env var; in a non-interactive shell (e.g. CI) with neither set, the prompt
# is skipped and the existing versionName is left as-is.
#
# The separate build number (versionCode, the plain integer Android uses internally to tell APKs
# apart) is bumped by one automatically every build so installing a new APK always upgrades over
# the last one -- you shouldn't need to think about it. Override it explicitly if you ever need
# to with --build-number=N or NIIX_BUILD_NUMBER.
#
# UPDATE-CHECKER SIGNING (optional, separate from APK signing):
#   --update-key=PATH / NIIX_UPDATE_KEY   path to the Ed25519 PRIVATE key (e.g.
#     niix-update-signing.pem, from `openssl genpkey -algorithm ed25519 -out niix-update-signing.pem`)
#     that matches RELEASE_SIGNING_PUBLIC_KEY hardcoded in UpdateChecker.kt. When set, after a
#     successful build this signs the APK (openssl pkeyutl -sign -rawin, the exact format
#     UpdateChecker verifies) producing niix-<version>.apk.sig, and writes SHA256SUMS.txt
#     alongside it. Needs `openssl`. This key is NOT your Android release keystore
#     (keystore.properties/niix-release.jks) -- they are unrelated, and losing or leaking either
#     one is a real problem, but compromising one does not compromise the other.
#   --publish-release / NIIX_PUBLISH_RELEASE=1   after signing, also create (or update, if it
#     already exists) a GitHub release tagged v<version> and upload the .apk, .sig, and
#     SHA256SUMS.txt as release assets, via the `gh` CLI (must already be installed and
#     authenticated: `gh auth login`). Requires --update-key to also be set -- publishing an
#     unsigned update would defeat the whole point. This is a real network call, unlike the
#     rest of this script (which only ever talks to your toolchain download mirrors).
#
# Optional overrides (export before running):
#   NIIX_TOOLCHAIN     install location (default: <project>/.toolchain)
#   NIIX_VERSION_NAME  version text for the APK, same as --version
#   NIIX_BUILD_NUMBER  build number (versionCode) for the APK, same as --build-number
#   NIIX_UPDATE_KEY    path to the update-checker signing key, same as --update-key
#   NIIX_PUBLISH_RELEASE=1   same as --publish-release
#   NIIX_SIGNING_DIR   signing directory location, same as --signing-dir
#   JDK_URL            JDK 17 tarball URL
#   GRADLE_URL         Gradle 8.11.1 distribution URL
#   CMDLINE_TOOLS_URL  Android command-line tools zip URL

set -euo pipefail

GRADLE_VERSION="8.14.4"
ANDROID_PLATFORM="android-35"
ANDROID_BUILD_TOOLS="35.0.0"
DEFAULT_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_BASE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
ADOPTIUM_API="https://api.adoptium.net/v3/binary/latest/17/ga/linux"

SETUP_ONLY=0
UPDATE=0
DEBUG_BUILD=0
PROJECT_ARG=""
VERSION_NAME_ARG=""
VERSION_NAME=""
VERSION_NAME_STATE=""
BUILD_NUMBER_ARG=""
BUILD_NUMBER=""
BUILD_NUMBER_STATE=""
UPDATE_KEY_ARG=""
PUBLISH_RELEASE=0
UPDATE_KEY=""
SIGNING_DIR_ARG=""
SIGNING_DIR=""

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
            --version=*) VERSION_NAME_ARG="${arg#--version=}" ;;
            --build-number=*) BUILD_NUMBER_ARG="${arg#--build-number=}" ;;
            --update-key=*) UPDATE_KEY_ARG="${arg#--update-key=}" ;;
            --publish-release) PUBLISH_RELEASE=1 ;;
            --keystore-backup=*) SIGNING_DIR_ARG="${arg#--keystore-backup=}" ;;
            --signing-dir=*) SIGNING_DIR_ARG="${arg#--signing-dir=}" ;;
            -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
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

prompt_version_name() {
    # Resolve a concrete value from the highest-priority source available, always ending up
    # with something real in VERSION_NAME -- it's also used to name the built APK file, so it
    # can't be left blank even on the "nothing given, non-interactive" path.
    local current
    current="$(cat "$VERSION_NAME_STATE" 2>/dev/null || true)"
    if [ -z "$current" ]; then
        current="$(grep -oE 'versionName = versionNameInput \?: "[^"]*"' "$PROJECT_DIR/app/build.gradle.kts" 2>/dev/null | sed -E 's/.*"(.*)"/\1/' | head -1)"
    fi
    current="${current:-0.1.0}"

    if [ -n "${NIIX_VERSION_NAME:-}" ]; then
        VERSION_NAME="$NIIX_VERSION_NAME"
        ok "Version: $VERSION_NAME (from NIIX_VERSION_NAME)"
    elif [ -n "$VERSION_NAME_ARG" ]; then
        VERSION_NAME="$VERSION_NAME_ARG"
        ok "Version: $VERSION_NAME (from --version)"
    elif [ ! -t 0 ]; then
        VERSION_NAME="$current"
        warn "Non-interactive shell and no --version/NIIX_VERSION_NAME given; using $VERSION_NAME"
    else
        local input
        read -r -p "Version for this APK (versionName -- any text, e.g. 0.2.0 or '1.3.6 b') [$current]: " input < /dev/tty || true
        VERSION_NAME="${input:-$current}"
        ok "Version: $VERSION_NAME"
    fi
    printf '%s\n' "$VERSION_NAME" > "$VERSION_NAME_STATE" 2>/dev/null || true
}

resolve_signing_dir() {
    if [ -n "${NIIX_SIGNING_DIR:-}" ]; then
        SIGNING_DIR="$NIIX_SIGNING_DIR"
    elif [ -n "$SIGNING_DIR_ARG" ]; then
        SIGNING_DIR="$SIGNING_DIR_ARG"
    else
        SIGNING_DIR="$HOME/Documents/niix-signing"
    fi
    case "$SIGNING_DIR" in
        /*) : ;;
        *) SIGNING_DIR="$PWD/$SIGNING_DIR" ;;
    esac
    log "Signing directory: $SIGNING_DIR"
}

# Gets a signed release build ready to happen, and makes the SAME signing material usable from
# any machine: everything security-sensitive (the Android release keystore, its properties, and
# the update-checker's Ed25519 key -- see resolve_update_key below) lives ONLY in SIGNING_DIR,
# never copied into this project checkout. Point SIGNING_DIR at removable media (a USB drive,
# say) and the exact same key material follows you between machines; the project directory
# itself -- which you might delete, re-clone from GitHub, or hand to someone else -- never has
# a copy of anything secret in it.
#
# If SIGNING_DIR already has a keystore.properties, this just verifies it opens and points
# Gradle at it (via NIIX_KEYSTORE_PROPERTIES, read by app/build.gradle.kts). If not, this
# generates a new keystore + properties file DIRECTLY in SIGNING_DIR (prompting for a password)
# -- nothing is ever written to the project directory at all.
setup_keystore() {
    local keystore_file="$SIGNING_DIR/niix-release.jks"
    local props_file="$SIGNING_DIR/keystore.properties"
    local alias="niix"

    if [ -f "$props_file" ]; then
        [ -f "$keystore_file" ] || die "$props_file exists but $keystore_file doesn't -- SIGNING_DIR is in an inconsistent state. Fix or remove $props_file, or point --signing-dir at the right location."
        local store_password; store_password="$(grep -E '^storePassword=' "$props_file" | cut -d= -f2-)"
        if "$JAVA_HOME/bin/keytool" -list -storetype PKCS12 -keystore "$keystore_file" -storepass "$store_password" >/tmp/keytool-verify.$$ 2>&1; then
            rm -f /tmp/keytool-verify.$$
            ok "Found existing release keystore in $SIGNING_DIR (alias: $(grep -E '^keyAlias=' "$props_file" | cut -d= -f2-))"
        else
            cat /tmp/keytool-verify.$$; rm -f /tmp/keytool-verify.$$
            die "Keystore in $SIGNING_DIR failed to open with the password in $props_file. It may be corrupt, or SIGNING_DIR points somewhere stale."
        fi
        export NIIX_KEYSTORE_PROPERTIES="$props_file"
        return 0
    fi

    warn "No keystore.properties in $SIGNING_DIR -- generating a new release keystore there."
    [ -f "$keystore_file" ] && die "$keystore_file exists but $props_file doesn't -- SIGNING_DIR is in an inconsistent state. Fix or remove $keystore_file manually."
    if [ ! -t 0 ]; then
        die "No keystore in $SIGNING_DIR, and this is a non-interactive shell so I can't prompt for a password. Run this interactively once first, or point --signing-dir / NIIX_SIGNING_DIR at a location that already has one (e.g. a mounted USB drive with your existing keys)."
    fi

    echo "Set a password for the new release keystore."
    echo "(leave blank to auto-generate a random one -- it will be printed once, and saved into $props_file)"
    local store_password
    read -rs -p "Keystore password: " store_password < /dev/tty
    echo
    if [ -z "$store_password" ]; then
        store_password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
        warn "Generated random keystore password."
    fi
    local key_password="$store_password"

    mkdir -p "$SIGNING_DIR"
    log "Generating $keystore_file (alias: $alias, RSA 4096, valid 10000 days)..."
    "$JAVA_HOME/bin/keytool" -genkeypair -v -storetype PKCS12 \
        -keystore "$keystore_file" \
        -alias "$alias" \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$store_password" \
        -keypass "$key_password" \
        -dname "CN=NiiX, OU=NiiX, O=NiiX, L=Unknown, ST=Unknown, C=XX" \
        >/tmp/keytool-out.$$ 2>&1 || { cat /tmp/keytool-out.$$; rm -f /tmp/keytool-out.$$; die "keytool failed."; }
    rm -f /tmp/keytool-out.$$
    ok "$keystore_file created."

    # storeFile is an ABSOLUTE path here (unlike keystore.properties.example's plain filename)
    # since this file doesn't live in the project directory and Gradle needs to find the .jks
    # regardless of what directory the build is actually run from.
    cat > "$props_file" <<PROPSEOF
storeFile=$keystore_file
storePassword=$store_password
keyAlias=$alias
keyPassword=$key_password
PROPSEOF
    chmod 700 "$SIGNING_DIR"
    chmod 600 "$props_file" "$keystore_file"
    ok "$props_file written (chmod 600)."
    warn "$SIGNING_DIR is still just one disk -- copy it somewhere else too (a second USB drive, password manager attachment, cloud storage you control). Losing it means losing the ability to sign updates as this app."

    export NIIX_KEYSTORE_PROPERTIES="$props_file"
}

# The update-checker's Ed25519 signing key (separate from the Android keystore above) also
# lives in SIGNING_DIR by default -- niix-update-signing.pem -- so it travels with the same USB
# drive / folder. --update-key or NIIX_UPDATE_KEY still override this if you keep it elsewhere.
resolve_update_key() {
    if [ -n "${NIIX_UPDATE_KEY:-}" ]; then
        UPDATE_KEY="$NIIX_UPDATE_KEY"
    elif [ -n "$UPDATE_KEY_ARG" ]; then
        UPDATE_KEY="$UPDATE_KEY_ARG"
    elif [ -f "$SIGNING_DIR/niix-update-signing.pem" ]; then
        UPDATE_KEY="$SIGNING_DIR/niix-update-signing.pem"
    fi
    if [ -n "${NIIX_PUBLISH_RELEASE:-}" ] && [ "$NIIX_PUBLISH_RELEASE" = "1" ]; then
        PUBLISH_RELEASE=1
    fi
    if [ -n "$UPDATE_KEY" ]; then
        [ -f "$UPDATE_KEY" ] || die "Update signing key not found: $UPDATE_KEY"
        ok "Update-checker signing key: $UPDATE_KEY"
    fi
    if [ "$PUBLISH_RELEASE" -eq 1 ] && [ -z "$UPDATE_KEY" ]; then
        die "--publish-release requires an update-checker signing key too (checked in $SIGNING_DIR/niix-update-signing.pem, or set --update-key) -- publishing an unsigned update would defeat the whole point of the update checker's signature check."
    fi
}

resolve_build_number() {
    if [ -n "${NIIX_BUILD_NUMBER:-}" ]; then
        BUILD_NUMBER="$NIIX_BUILD_NUMBER"
    elif [ -n "$BUILD_NUMBER_ARG" ]; then
        BUILD_NUMBER="$BUILD_NUMBER_ARG"
    else
        # Auto-bump so a freshly built APK always installs as an upgrade over the last one,
        # without needing to ask -- this is the plain internal integer, not the version text.
        # Remembered across runs (rather than re-read from app/build.gradle.kts, which only
        # ever holds the fixed fallback default, not whatever was actually last built).
        local current
        current="$(cat "$BUILD_NUMBER_STATE" 2>/dev/null || true)"
        if [ -z "$current" ]; then
            current="$(grep -oE '\} \?: [0-9]+' "$PROJECT_DIR/app/build.gradle.kts" 2>/dev/null | grep -oE '[0-9]+' | head -1)"
        fi
        BUILD_NUMBER=$(( ${current:-0} + 1 ))
    fi
    if ! [[ "$BUILD_NUMBER" =~ ^[0-9]+$ ]]; then
        die "Build number (versionCode) must be a whole non-negative number, got: '$BUILD_NUMBER'. Version text (0.2.0, 1.3.6 b, ...) goes in --version instead."
    fi
    ok "Build number: $BUILD_NUMBER"
    printf '%s\n' "$BUILD_NUMBER" > "$BUILD_NUMBER_STATE" 2>/dev/null || true
}

build_apk() {
    local -a version_args=("-PversionCode=$BUILD_NUMBER")
    if [ -n "$VERSION_NAME" ]; then
        version_args+=("-PversionName=$VERSION_NAME")
    fi

    if [ "$DEBUG_BUILD" -eq 1 ]; then
        log "Building :app:assembleDebug (--debug requested; not for real use, no signing needed)"
        JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_SDK_ROOT" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
            "${GRADLE_CMD:-$GRADLE_BIN}" -p "$PROJECT_DIR" --no-daemon "${version_args[@]}" :app:assembleDebug

        local apk="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
        [ -f "$apk" ] || die "Build finished but APK not found at $apk"
        apk="$(rename_apk "$apk")"
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
        "${GRADLE_CMD:-$GRADLE_BIN}" -p "$PROJECT_DIR" --no-daemon "${version_args[@]}" :app:assembleRelease

    local apk="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
    [ -f "$apk" ] || die "Build finished but APK not found at $apk"
    apk="$(rename_apk "$apk")"
    sign_update "$apk"
    publish_release "$apk"
    local size; size="$(du -h "$apk" | awk '{print $1}')"
    printf '\n%s================================================================%s\n' "$c_grn" "$c_rst"
    ok "Signed release APK built: $apk ($size)"
    printf '\nInstall on a connected device (USB debugging on):\n  %s/platform-tools/adb install -r "%s"\n' "$ANDROID_SDK_ROOT" "$apk"
    printf '\nRebuild later without re-running setup:\n  source "%s/env.sh" && (cd "%s" && gradle :app:assembleRelease)\n' "$TOOLCHAIN" "$PROJECT_DIR"
}

# Renames Gradle's default app-release.apk/app-debug.apk to niix-<version>.apk, in place,
# printing the new path. "/" is the only character truly unsafe in a filename here (spaces are
# fine on Linux/most filesystems) so it's the only one stripped out of the version text.
rename_apk() {
    local original="$1"
    local safe_version; safe_version="$(printf '%s' "$VERSION_NAME" | tr '/' '-')"
    local renamed; renamed="$(dirname "$original")/niix-${safe_version}.apk"
    mv -f "$original" "$renamed"
    printf '%s' "$renamed"
}

# Signs the built APK for the in-app update checker (a separate Ed25519 keypair from the
# Android APK-signing keystore -- see the --update-key usage docs at the top of this file) and
# writes SHA256SUMS.txt alongside it. No-op if no update key was configured.
sign_update() {
    local apk="$1"
    [ -n "$UPDATE_KEY" ] || return 0
    have openssl || die "openssl not found -- needed for --update-key signing. Install with: sudo pacman -S --needed openssl"

    local sig="${apk}.sig"
    log "Signing $(basename "$apk") for the in-app update checker"
    openssl pkeyutl -sign -inkey "$UPDATE_KEY" -rawin -in "$apk" -out "$sig" \
        || die "openssl signing failed -- is $UPDATE_KEY a valid Ed25519 private key?"
    ok "Wrote $(basename "$sig")"

    local checksums; checksums="$(dirname "$apk")/SHA256SUMS.txt"
    ( cd "$(dirname "$apk")" && sha256sum "$(basename "$apk")" > "$checksums" )
    ok "Wrote $(basename "$checksums")"
}

# Creates a new GitHub release (or uploads to it if it already exists) and attaches the APK,
# its .sig, and SHA256SUMS.txt as release assets, via the gh CLI. No-op unless --publish-release
# was given; resolve_update_key() already enforced that --update-key is set whenever this runs.
publish_release() {
    local apk="$1"
    [ "$PUBLISH_RELEASE" -eq 1 ] || return 0
    have gh || die "gh (GitHub CLI) not found -- needed for --publish-release. Install with: sudo pacman -S --needed github-cli, then run: gh auth login"

    # The release's git tag has to be exactly "v$VERSION_NAME" for the in-app update checker's
    # string comparison to work (it strips a leading "v" and compares directly against the
    # running app's versionName) -- so VERSION_NAME has to be a valid git tag component once
    # that "v" is added. Spaces and a few other characters are allowed in a plain versionName
    # (e.g. "1.3.6 b") but are NOT allowed in a git ref, so silently substituting them here would
    # produce a tag that no longer matches the APK's actual versionName, and the update checker
    # would then either never recognize this build as current, or never recognize a future one as
    # newer. Failing loudly now is much better than a silently-broken update check discovered
    # later, so this is checked before anything is uploaded.
    case "$VERSION_NAME" in
        *' '*|*'~'*|*'^'*|*':'*|*'?'*|*'*'*|*'['*|*'\'*|*'..'*)
            die "Version \"$VERSION_NAME\" contains a character that isn't valid in a git tag (e.g. a space), so it can't be published as a GitHub release -- the release tag must exactly match the APK's versionName for the in-app update checker to work. Re-run with --version set to something like \"1.3.6\" (no spaces or ~^:?*[\\) for a build you intend to publish."
            ;;
    esac

    local sig="${apk}.sig"
    local checksums; checksums="$(dirname "$apk")/SHA256SUMS.txt"
    [ -f "$sig" ] || die "No signature file at $sig -- sign_update should have produced this before publish_release runs"
    [ -f "$checksums" ] || die "No checksums file at $checksums -- sign_update should have produced this before publish_release runs"

    local tag="v${VERSION_NAME}"
    log "Publishing GitHub release $tag"
    if gh release view "$tag" >/dev/null 2>&1; then
        gh release upload "$tag" "$apk" "$sig" "$checksums" --clobber \
            || die "gh release upload failed"
        ok "Updated existing release $tag with new assets"
    else
        gh release create "$tag" "$apk" "$sig" "$checksums" \
            --title "niix $VERSION_NAME" \
            --notes "Automated build via build-niix.sh. The in-app update checker verifies $(basename "$sig") against its pinned Ed25519 public key automatically before ever offering this for install." \
            || die "gh release create failed"
        ok "Published new release $tag"
    fi
    printf '\n%s\n' "https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo '(repo)')/releases/tag/$tag"
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
    VERSION_NAME_STATE="$TOOLCHAIN/last-version-name"
    BUILD_NUMBER_STATE="$TOOLCHAIN/last-build-number"
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
    if [ "$DEBUG_BUILD" -ne 1 ]; then
        resolve_signing_dir
        setup_keystore
    fi
    prompt_version_name
    resolve_build_number
    resolve_update_key
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
