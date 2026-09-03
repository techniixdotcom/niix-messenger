#!/usr/bin/env bash
# build-niix.sh -- build the app.
#
#   ./build-niix.sh                          Interactive menu.
#   ./build-niix.sh --release                Signed release APK (needs your own
#                                             keystore.properties -- see README.md).
#   ./build-niix.sh --debug                  Debug APK, no signing key needed.
#   ./build-niix.sh --verify-reproducible    Unsigned release APK from source alone, for
#                                             comparing its hash against someone else's build
#                                             of the same commit.
#
#   NIIX_VERSION_NAME=1.2.0 NIIX_VERSION_CODE=5 ./build-niix.sh --release
#                                             Override version name/code for this build.
#
# Makes sure a JDK, Gradle, and the Android SDK actually exist before trying to build anything.
# If your machine already has them (JAVA_HOME / a system `gradle` / an existing Android SDK),
# those are used as-is. Anything missing is downloaded into ~/.niix-toolchain (override with
# NIIX_TOOLCHAIN_DIR) rather than into the project, so deleting or re-extracting the project
# folder doesn't throw the download away -- and nothing is ever installed system-wide, so this
# never needs sudo and never conflicts with an existing install.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
ROOT="$(pwd)"

# The toolchain (JDK, Gradle, Android SDK) lives outside the project so deleting or re-extracting
# the project folder doesn't throw away a large download you'd only have to fetch again. Override
# with NIIX_TOOLCHAIN_DIR. An existing .toolchain inside the project is still honoured.
if [ -n "${NIIX_TOOLCHAIN_DIR:-}" ]; then
  TOOLCHAIN="$NIIX_TOOLCHAIN_DIR"
elif [ -d "$ROOT/.toolchain" ]; then
  TOOLCHAIN="$ROOT/.toolchain"
else
  TOOLCHAIN="$HOME/.niix-toolchain"
fi
mkdir -p "$TOOLCHAIN"

LOG_FILE="$ROOT/build.log"
exec > >(tee "$LOG_FILE") 2>&1

on_exit() {
  local code=$?
  echo
  echo "Full output of this run was saved to: $LOG_FILE"
  if [ $code -ne 0 ]; then
    echo "This run failed (exit $code) -- that file has everything, including the parts that"
    echo "scrolled by."
  fi
  exit $code
}
trap on_exit EXIT

# Everything printed here goes through `tee` into build.log. That pipe is block-buffered, so a
# prompt written to stdout would sit in the buffer instead of reaching the terminal -- `read`
# would then block for input while showing nothing, looking like a hang. Writing to and reading
# from /dev/tty bypasses the redirection; the fallback covers non-interactive runs where
# /dev/tty can't be opened.
ask() {
  local prompt="$1" varname="$2" answer
  if { true > /dev/tty; } 2>/dev/null; then
    printf '%s' "$prompt" > /dev/tty
    IFS= read -r answer < /dev/tty || answer=""
  else
    printf '%s' "$prompt"
    IFS= read -r answer || answer=""
  fi
  printf -v "$varname" '%s' "$answer"
}

MODE="release"

show_menu() {
  local choice
  ask "$(printf 'What would you like to build?\n  1) Signed release APK\n  2) Debug APK (no signing key needed)\n  3) Unsigned APK for reproducible-build verification\nEnter a number [1-3]: ')" choice
  case "$choice" in
    1) MODE="release" ;;
    2) MODE="debug" ;;
    3) MODE="reproducible" ;;
    *)
      echo "error: '$choice' isn't one of the options above." >&2
      exit 1
      ;;
  esac
}

if [ $# -eq 0 ]; then
  show_menu
else
  for arg in "$@"; do
    case "$arg" in
      --verify-reproducible) MODE="reproducible" ;;
      --debug) MODE="debug" ;;
      --release) MODE="release" ;;
      -h|--help)
        sed -n '2,17p' "${BASH_SOURCE[0]}"
        exit 0
        ;;
      *)
        echo "Unknown argument: $arg (see --help)" >&2
        exit 1
        ;;
    esac
  done
fi

detect_os() {
  case "$(uname -s)" in
    Linux) echo linux ;;
    Darwin) echo mac ;;
    *) echo unsupported ;;
  esac
}

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64) echo x64 ;;
    aarch64|arm64) echo aarch64 ;;
    *) echo unsupported ;;
  esac
}

OS="$(detect_os)"
ARCH="$(detect_arch)"

ensure_jdk() {
  if [ -x "$TOOLCHAIN/jdk/bin/java" ]; then
    export JAVA_HOME="$TOOLCHAIN/jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  if command -v java >/dev/null 2>&1; then
    local ver
    ver=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || true)
    if [ -n "$ver" ] && [ "$ver" -ge 17 ]; then
      return 0
    fi
  fi

  echo "==> No usable JDK 17+ found -- downloading Eclipse Temurin 17..."
  if [ "$OS" = "unsupported" ] || [ "$ARCH" = "unsupported" ]; then
    echo "error: can't auto-download a JDK for this OS/architecture ($(uname -s) $(uname -m))." >&2
    echo "       Install a JDK 17 yourself and re-run, or set JAVA_HOME to one you already have." >&2
    exit 1
  fi

  local url="https://api.adoptium.net/v3/binary/latest/17/ga/${OS}/${ARCH}/jdk/hotspot/normal/eclipse"
  if ! curl -fsSL "$url" -o "$TOOLCHAIN/jdk.tar.gz"; then
    echo "error: JDK download failed (tried $url)." >&2
    echo "       Install a JDK 17 yourself and re-run, or set JAVA_HOME to one you already have." >&2
    exit 1
  fi
  rm -rf "$TOOLCHAIN/jdk"
  mkdir -p "$TOOLCHAIN/jdk"
  tar -xzf "$TOOLCHAIN/jdk.tar.gz" -C "$TOOLCHAIN/jdk" --strip-components=1
  rm -f "$TOOLCHAIN/jdk.tar.gz"
  export JAVA_HOME="$TOOLCHAIN/jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "==> JDK ready at $JAVA_HOME"
}

GRADLE_CMD="./gradlew"

ensure_gradle() {
  if [ -f gradle/wrapper/gradle-wrapper.jar ] && [ -f gradlew ]; then
    chmod +x gradlew
    GRADLE_CMD="./gradlew"
    return 0
  fi

  echo "==> gradle-wrapper.jar is missing -- bootstrapping a local Gradle to regenerate it..."
  local dist_url
  dist_url=$(grep '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties | cut -d= -f2- | sed 's/\\:/:/g')
  if [ -z "$dist_url" ]; then
    echo "error: couldn't read distributionUrl from gradle/wrapper/gradle-wrapper.properties." >&2
    exit 1
  fi

  if [ ! -x "$TOOLCHAIN/gradle/bin/gradle" ]; then
    if ! curl -fsSL "$dist_url" -o "$TOOLCHAIN/gradle.zip"; then
      echo "error: Gradle download failed (tried $dist_url)." >&2
      echo "       Install Gradle 8.14.4 yourself and run 'gradle wrapper' in this directory," >&2
      echo "       then re-run this script." >&2
      exit 1
    fi
    rm -rf "$TOOLCHAIN/gradle-unzipped" "$TOOLCHAIN/gradle"
    mkdir -p "$TOOLCHAIN/gradle-unzipped"
    unzip -q "$TOOLCHAIN/gradle.zip" -d "$TOOLCHAIN/gradle-unzipped"
    rm -f "$TOOLCHAIN/gradle.zip"
    mv "$TOOLCHAIN"/gradle-unzipped/gradle-* "$TOOLCHAIN/gradle"
    rmdir "$TOOLCHAIN/gradle-unzipped" 2>/dev/null || true
  fi

  echo "==> Regenerating gradle-wrapper.jar with the pinned Gradle version..."
  "$TOOLCHAIN/gradle/bin/gradle" --console=plain wrapper --gradle-distribution-url "$dist_url"

  if [ -f gradle/wrapper/gradle-wrapper.jar ]; then
    chmod +x gradlew
    GRADLE_CMD="./gradlew"
  else
    GRADLE_CMD="$TOOLCHAIN/gradle/bin/gradle"
  fi
  echo "==> Gradle ready ($GRADLE_CMD)"
}

ensure_android_sdk() {
  local existing="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$existing" ] && [ -d "$existing/platforms" ]; then
    echo "sdk.dir=$existing" > local.properties
    return 0
  fi
  if [ -f local.properties ] && grep -q '^sdk.dir=' local.properties; then
    local existing_dir
    existing_dir=$(grep '^sdk.dir=' local.properties | cut -d= -f2-)
    if [ -d "$existing_dir/platforms" ]; then
      return 0
    fi
  fi
  for guess in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$guess/platforms" ]; then
      echo "sdk.dir=$guess" > local.properties
      return 0
    fi
  done
  if [ -d "$TOOLCHAIN/android-sdk/platforms" ]; then
    echo "sdk.dir=$TOOLCHAIN/android-sdk" > local.properties
    return 0
  fi

  echo "==> No Android SDK found -- downloading Android SDK command-line tools..."
  if [ "$OS" = "unsupported" ]; then
    echo "error: can't auto-download the Android SDK for this OS ($(uname -s))." >&2
    echo "       Install Android Studio (or the SDK on its own), then either set ANDROID_HOME" >&2
    echo "       or put sdk.dir=<path to your SDK> in local.properties yourself." >&2
    exit 1
  fi

  local sdk_dir="$TOOLCHAIN/android-sdk"
  mkdir -p "$sdk_dir/cmdline-tools"
  local zip_url="https://dl.google.com/android/repository/commandlinetools-${OS}-11076708_latest.zip"
  if ! curl -fsSL "$zip_url" -o "$TOOLCHAIN/cmdline-tools.zip"; then
    echo "error: Android SDK command-line tools download failed (tried $zip_url)." >&2
    echo "       Get a current download link from" >&2
    echo "       https://developer.android.com/studio#command-line-tools-only" >&2
    echo "       unzip it into $sdk_dir/cmdline-tools/latest (so that path contains bin/, lib/," >&2
    echo "       etc. directly), then re-run this script." >&2
    exit 1
  fi
  rm -rf "$sdk_dir/cmdline-tools/latest"
  unzip -q "$TOOLCHAIN/cmdline-tools.zip" -d "$sdk_dir/cmdline-tools"
  rm -f "$TOOLCHAIN/cmdline-tools.zip"
  mv "$sdk_dir/cmdline-tools/cmdline-tools" "$sdk_dir/cmdline-tools/latest"

  local sdkmanager="$sdk_dir/cmdline-tools/latest/bin/sdkmanager"
  chmod +x "$sdkmanager"
  echo "==> Accepting SDK licenses and installing platform 35 + build-tools (a few minutes)..."
  yes | "$sdkmanager" --sdk_root="$sdk_dir" --licenses >/dev/null 2>&1 || true
  # `yes |` here is not redundant with the --licenses call above: installing packages prompts
  # again for their own licenses, and that prompt is invisible because everything this script
  # prints goes through a buffered tee pipe -- so without this the script sits there forever,
  # apparently hung, waiting on input nobody can see it asking for.
  #
  # pipefail is disabled for exactly this one pipeline. When sdkmanager finishes it closes its
  # stdin, `yes` is killed by SIGPIPE, and that exit status (141) becomes the pipeline's under
  # pipefail -- which aborts the whole script under `set -e` even though the install actually
  # succeeded. PIPESTATUS[1] gets sdkmanager's own status, which is the one that matters.
  set +o pipefail
  # The NDK is required even though this project contains no native source of its own. The
  # Android plugin strips debug symbols from bundled prebuilt .so files using the NDK's strip
  # tool, and without the NDK present it silently gives up -- "Unable to strip the following
  # libraries, packaging them as they are" -- and ships libsignal, SQLCipher and tor complete
  # with .debug_info and .symtab sections. That is a real finding in a security release: it
  # hands a reverse engineer the full symbol tables of exactly the security-sensitive code.
  yes | "$sdkmanager" --sdk_root="$sdk_dir" "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973"
  local sdk_rc=${PIPESTATUS[1]}
  set -o pipefail
  if [ "$sdk_rc" -ne 0 ]; then
    echo "error: sdkmanager failed to install the required SDK packages (exit $sdk_rc)." >&2
    exit 1
  fi

  echo "sdk.dir=$sdk_dir" > local.properties
  echo "==> Android SDK ready at $sdk_dir"
}

read_version_name() {
  sed -n 's/^versionName=\(.*\)/\1/p' version.properties 2>/dev/null
}

read_version_code() {
  sed -n 's/^versionCode=\(.*\)/\1/p' version.properties 2>/dev/null
}

# Asks what version this build should have, suggesting the next patch version as a default.
# version.properties in the project root is what the build actually reads.
# NIIX_VERSION_NAME / NIIX_VERSION_CODE in the environment skip the prompts, for scripted builds.
choose_version() {
  local old_name old_code suggested_name new_name new_code
  old_name="$(read_version_name)"
  old_code="$(read_version_code)"
  old_name="${old_name:-0.1.0}"
  old_code="${old_code:-0}"
  if [[ "$old_name" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    suggested_name="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$(( BASH_REMATCH[3] + 1 ))"
  else
    suggested_name="$old_name"
  fi

  new_name="${NIIX_VERSION_NAME:-}"
  if [ -z "$new_name" ]; then
    ask "Version name for this build (currently $old_name) [$suggested_name]: " new_name
    new_name="${new_name:-$suggested_name}"
  fi

  new_code="${NIIX_VERSION_CODE:-}"
  if [ -z "$new_code" ]; then
    local suggested_code=$((old_code + 1))
    ask "Version code for this build (currently $old_code) [$suggested_code]: " new_code
    new_code="${new_code:-$suggested_code}"
  fi
  if ! [[ "$new_code" =~ ^[0-9]+$ ]]; then
    echo "error: version code must be a whole number, got '$new_code'." >&2
    exit 1
  fi
  if [ "$new_code" -le "$old_code" ]; then
    echo "warning: version code $new_code is not greater than the current $old_code. This build"
    echo "         won't install over an existing install as an update -- you'd have to"
    echo "         uninstall first."
  fi

  {
    echo "versionName=$new_name"
    echo "versionCode=$new_code"
  } > version.properties
  echo "==> Version set: $old_name ($old_code) -> $new_name ($new_code)"
  export NIIX_VERSION_NAME="$new_name"
  export NIIX_VERSION_CODE="$new_code"
}

rename_output_apk() {
  local original="$1"
  local version_name
  version_name="$(read_version_name)"
  version_name="${version_name:-0.1.0}"
  local dir
  dir="$(dirname "$original")"
  # Clear out renamed APKs from previous builds so the output directory only ever holds the
  # current one -- otherwise it accumulates one per version and it stops being obvious which
  # file is the build you just made.
  find "$dir" -maxdepth 1 -name "niix-messenger-*.apk" -delete
  local renamed="$dir/niix-messenger-${version_name}.apk"
  cp -f "$original" "$renamed"
  echo "$renamed"
}

# Runs the same structural/signature check Android's own installer does, right after building,
# so a broken APK is caught here with a clear diagnosis instead of only surfacing later as
# "there was a problem while parsing the package" on someone's phone with no indication why.
verify_apk() {
  local apk_path="$1"
  local sdk_root=""
  if [ -f local.properties ]; then
    sdk_root=$(sed -n 's/^sdk\.dir=//p' local.properties)
  fi
  if [ -z "$sdk_root" ] || [ ! -d "$sdk_root" ]; then
    echo "warning: couldn't determine the Android SDK location -- skipping apksigner verification" >&2
    echo "         of $apk_path. This doesn't mean it's broken, just that it wasn't checked." >&2
    return 0
  fi
  local apksigner
  apksigner=$(find "$sdk_root/build-tools" -maxdepth 2 -name "apksigner" -type f 2>/dev/null | sort -V | tail -1)
  if [ -z "$apksigner" ]; then
    echo "warning: couldn't find apksigner under $sdk_root/build-tools -- skipping verification" >&2
    echo "         of $apk_path." >&2
    return 0
  fi
  echo "==> Verifying $apk_path with apksigner (the same check Android's installer does)..."
  if ! "$apksigner" verify "$apk_path"; then
    echo "error: apksigner rejected $apk_path -- Android would refuse to install this too," >&2
    echo "       with something like \"There was a problem while parsing the package.\"" >&2
    echo "       Try a clean build: rm -rf app/build && ./build-niix.sh" >&2
    exit 1
  fi
  echo "==> apksigner confirms this APK is structurally valid and correctly signed."
}

echo "==> Checking build tools..."
ensure_jdk
ensure_gradle
ensure_android_sdk

case "$MODE" in
  debug)
    echo "==> Building debug APK (no signing key required)..."
    "$GRADLE_CMD" --console=plain :app:assembleDebug
    OUT=$(find app/build/outputs/apk/debug -name "*.apk" ! -name "niix-messenger-*" | head -1)
    if [ -n "$OUT" ]; then
      OUT="$(rename_output_apk "$OUT")"
      verify_apk "$OUT"
    fi
    ;;

  reproducible)
    echo "==> Building unsigned release APK for hash comparison (-PniixUnsigned=true)..."
    echo "    This never reads keystore.properties, even if one exists."
    "$GRADLE_CMD" --console=plain :app:assembleRelease -PniixUnsigned=true
    OUT=$(find app/build/outputs/apk/release -name "*unsigned*.apk" | head -1)
    if [ -n "$OUT" ]; then
      echo "==> SHA-256: $(sha256sum "$OUT" | cut -d' ' -f1)"
      echo "    Compare this against another independent build of the exact same commit."
    fi
    ;;

  release)
    KEYSTORE_PROPERTIES="${NIIX_KEYSTORE_PROPERTIES:-keystore.properties}"
    if [ ! -f "$KEYSTORE_PROPERTIES" ]; then
      cat >&2 <<EOF
error: no keystore found at '$KEYSTORE_PROPERTIES'.

To build a signed release APK, either:
  1. Generate a keystore and a keystore.properties file:
       keytool -genkeypair -v -storetype PKCS12 -keystore niix-release.jks \\
         -alias niix -keyalg RSA -keysize 4096 -validity 10000
     Then create keystore.properties in the project root:
       storeFile=niix-release.jks
       storePassword=<your store password>
       keyAlias=niix
       keyPassword=<your key password>
  2. Or set NIIX_KEYSTORE_PROPERTIES to an absolute path to a keystore.properties file
     stored elsewhere (e.g. on removable media).

To build something installable right now without any of that, run:
  ./build-niix.sh --debug
EOF
      exit 1
    fi

    choose_version

    echo "==> Building signed release APK using $KEYSTORE_PROPERTIES..."
    if [ -n "${NIIX_KEYSTORE_PROPERTIES:-}" ]; then
      export NIIX_KEYSTORE_PROPERTIES
    fi
    GRADLE_ARGS=(--console=plain :app:assembleRelease)
    [ -n "${NIIX_VERSION_NAME:-}" ] && GRADLE_ARGS+=(-PversionName="$NIIX_VERSION_NAME")
    [ -n "${NIIX_VERSION_CODE:-}" ] && GRADLE_ARGS+=(-PversionCode="$NIIX_VERSION_CODE")
    "$GRADLE_CMD" "${GRADLE_ARGS[@]}"
    OUT=$(find app/build/outputs/apk/release -name "*.apk" ! -name "*unsigned*" ! -name "niix-messenger-*" | head -1)
    if [ -n "$OUT" ]; then
      OUT="$(rename_output_apk "$OUT")"
      verify_apk "$OUT"
    fi
    ;;
esac

echo
if [ -n "${OUT:-}" ] && [ -f "$OUT" ]; then
  echo "==> Built: $OUT"
  echo "==> Size: $(du -h "$OUT" | cut -f1)"
else
  echo "warning: build finished but the expected output APK wasn't found where I looked." >&2
  echo "         Check app/build/outputs/apk/ directly." >&2
  exit 1
fi
