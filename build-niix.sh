#!/usr/bin/env bash
# build-niix.sh -- build the app.
#
#   ./build-niix.sh                          Run the tests, then build a debug APK. Sets up
#                                             everything it needs first, so this works on a
#                                             fresh clone with nothing installed and no keys.
#   ./build-niix.sh --menu                   Interactive menu.
#   ./build-niix.sh --release                Signed release APK (needs your own
#                                             keystore.properties -- see README.md).
#   ./build-niix.sh --test                   Run the automated test suite. Sets up whatever it
#                                             needs first, so this works on a fresh checkout.
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
elif [ -d "$ROOT/.toolchain/jdk" ] || [ -d "$ROOT/.toolchain/android-sdk" ]; then
  # Only honoured when it actually holds a toolchain. Testing for the directory alone meant an
  # empty .toolchain -- which is trivially created, and was for a while shipped in the archive --
  # diverted every download back into the project folder. Anyone who re-extracts the project then
  # loses several hundred megabytes and re-downloads it on the next build, which is exactly the
  # problem moving the toolchain out was meant to solve.
  TOOLCHAIN="$ROOT/.toolchain"
else
  TOOLCHAIN="$HOME/.niix-toolchain"
fi
mkdir -p "$TOOLCHAIN"

# Test runs write to test.log instead of build.log, so a test result is not overwritten by the
# next build (or vice versa) -- the two are usually being compared, and having each clobber the
# other is exactly when you want both. Set before anything writes to the log.
case " $* " in
  *" --test "*) LOG_FILE="$ROOT/test.log" ;;
  *)            LOG_FILE="$ROOT/build.log" ;;
esac
exec > >(tee "$LOG_FILE") 2>&1

# Pulls the actual cause out of the log and reprints it at the very end.
#
# Gradle emits hundreds of lines of task status before a failure, so the real error scrolls well
# out of view and the last thing on screen is a generic "this run failed" -- which is close to
# useless. The log always contained the answer; it just was not where anyone would look. This
# repeats the salient part after everything else, so the final thing printed is the reason.
summarize_failure() {
  [ -f "$LOG_FILE" ] || return 0

  # Gradle's own structured failure block, which names the task and the problem.
  local gradle_block
  gradle_block=$(sed -n '/^FAILURE: Build failed/,/^\* Try:/p' "$LOG_FILE" 2>/dev/null | head -40)
  if [ -n "$gradle_block" ]; then
    echo
    echo "---- what actually failed ----"
    printf '%s\n' "$gradle_block" | grep -v '^\* Try:'
    return 0
  fi

  # Kotlin compile errors: report the first few, since later ones are usually consequences.
  local kotlin_errors
  kotlin_errors=$(grep -E "^e: " "$LOG_FILE" 2>/dev/null | head -5)
  if [ -n "$kotlin_errors" ]; then
    echo
    echo "---- what actually failed ----"
    printf '%s\n' "$kotlin_errors"
    local total
    total=$(grep -cE "^e: " "$LOG_FILE" 2>/dev/null || echo 0)
    [ "$total" -gt 5 ] && echo "  ... and $((total - 5)) more compile error(s) in the log."
    return 0
  fi

  # Anything this script itself raised.
  local script_errors
  script_errors=$(grep -E "^error: " "$LOG_FILE" 2>/dev/null | tail -5)
  if [ -n "$script_errors" ]; then
    echo
    echo "---- what actually failed ----"
    printf '%s\n' "$script_errors"
    return 0
  fi

  # Nothing matched. That is precisely when a summary is most useful: a run that fails with no
  # recognised error is usually the shell aborting under `set -e` -- a command returning
  # non-zero with nothing printed -- and showing "This run failed" alone gives no way to even
  # guess where. The last few lines at least locate it.
  echo
  echo "---- no recognised error; here are the last lines before it stopped ----"
  tail -12 "$LOG_FILE" 2>/dev/null | sed 's/^/    /'
  echo "    (a failure with no message is usually a command exiting non-zero under 'set -e')"
}

on_exit() {
  local code=$?
  echo
  echo "Full output of this run was saved to: $LOG_FILE"
  if [ $code -ne 0 ]; then
    echo "This run failed (exit $code)."
    summarize_failure
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
  ask "$(printf 'What would you like to build?\n  1) Signed release APK\n  2) Debug APK (no signing key needed)\n  3) Unsigned APK for reproducible-build verification\n  4) Run the test suite\nEnter a number [1-4]: ')" choice
  case "$choice" in
    1) MODE="release" ;;
    2) MODE="debug" ;;
    3) MODE="reproducible" ;;
    4) MODE="test" ;;
    *)
      echo "error: '$choice' isn't one of the options above." >&2
      exit 1
      ;;
  esac
}

if [ $# -eq 0 ]; then
  # No arguments runs everything a newcomer to this repo would want: set up the toolchain, run
  # the tests, then build an installable debug APK. Nothing needs to be installed first and no
  # signing key is required, so `./build-niix.sh` on a fresh clone just works. The menu is still
  # available with --menu for anyone who wants to pick a specific task.
  MODE="verify"
else
  for arg in "$@"; do
    case "$arg" in
      --verify-reproducible) MODE="reproducible" ;;
      --debug) MODE="debug" ;;
      --test) MODE="test" ;;
      --menu) show_menu ;;
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

# Re-point the log once the mode is actually known.
#
# The redirect above has to be established before anything can be logged, which is before
# the interactive menu has run -- so a test selected from the menu would otherwise still
# write to build.log, and only the --test flag would be honoured. Re-establishing it here
# means both routes end up in test.log. The few lines already written (the menu itself)
# stay in build.log, which is harmless.
if [ "$MODE" = "test" ] && [ "$LOG_FILE" != "$ROOT/test.log" ]; then
  LOG_FILE="$ROOT/test.log"
  : > "$LOG_FILE"
  exec > >(tee "$LOG_FILE") 2>&1
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

# Verifies a downloaded file against a SHA-256 hash.
#
# Fetching the published checksum catches a corrupted download or a tampered mirror, but not a
# compromised origin -- if the same party serves both file and checksum, they move together.
# Pinning a hash obtained independently (NIIX_GRADLE_SHA256, NIIX_JDK_SHA256,
# NIIX_CMDLINE_TOOLS_SHA256) is what resists that, and is what a high-assurance build should use.
verify_sha256() {
  local file="$1" expected="$2" label="$3"
  [ -z "$expected" ] && return 0
  local actual
  actual=$(sha256sum "$file" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    echo "error: $label failed checksum verification." >&2
    echo "       expected: $expected" >&2
    echo "       actual:   $actual" >&2
    rm -f "$file"
    exit 1
  fi
  echo "==> $label checksum verified."
}

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
  verify_sha256 "$TOOLCHAIN/jdk.tar.gz" "${NIIX_JDK_SHA256:-}" "JDK"

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

  # A Gradle that is already on the machine is preferred over downloading one. This matters for
  # restricted or offline environments -- a reviewer on a network that cannot reach
  # services.gradle.org previously hit a hard stop here and could not compile or run the tests
  # at all, even with a perfectly good Gradle installed. Downloading should be the fallback for
  # convenience, not a requirement.
  if [ -x "$TOOLCHAIN/gradle/bin/gradle" ]; then
    GRADLE_CMD="$TOOLCHAIN/gradle/bin/gradle"
    echo "==> Using the Gradle already in $TOOLCHAIN."
    return 0
  fi
  if command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="$(command -v gradle)"
    echo "==> Using the system Gradle at $GRADLE_CMD ($(gradle --version 2>/dev/null | awk '/^Gradle/ {print $2}'))."
    echo "    The wrapper jar is not in the repository (it is a binary, deliberately not"
    echo "    committed), so this runs Gradle directly instead."
    return 0
  fi

  # An already-downloaded distribution can also be supplied, for environments where fetching it
  # here is not possible but obtaining it separately is.
  if [ -n "${NIIX_GRADLE_ZIP:-}" ] && [ -f "$NIIX_GRADLE_ZIP" ]; then
    echo "==> Using the Gradle distribution supplied at $NIIX_GRADLE_ZIP"
    verify_sha256 "$NIIX_GRADLE_ZIP" "${NIIX_GRADLE_SHA256:-}" "Gradle distribution"
    rm -rf "$TOOLCHAIN/gradle-unzipped" "$TOOLCHAIN/gradle"
    mkdir -p "$TOOLCHAIN/gradle-unzipped"
    unzip -q "$NIIX_GRADLE_ZIP" -d "$TOOLCHAIN/gradle-unzipped"
    mv "$TOOLCHAIN"/gradle-unzipped/gradle-* "$TOOLCHAIN/gradle"
    rmdir "$TOOLCHAIN/gradle-unzipped" 2>/dev/null || true
    GRADLE_CMD="$TOOLCHAIN/gradle/bin/gradle"
    return 0
  fi

  echo "==> No Gradle found -- downloading one..."
  local dist_url
  dist_url=$(grep '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties | cut -d= -f2- | sed 's/\\:/:/g')
  if [ -z "$dist_url" ]; then
    echo "error: couldn't read distributionUrl from gradle/wrapper/gradle-wrapper.properties." >&2
    exit 1
  fi

  if [ ! -x "$TOOLCHAIN/gradle/bin/gradle" ]; then
    if ! curl -fsSL "$dist_url" -o "$TOOLCHAIN/gradle.zip"; then
      echo "error: could not download Gradle from $dist_url" >&2
      echo >&2
      echo "This machine cannot reach services.gradle.org. Any one of these will work instead:" >&2
      echo >&2
      echo "  1. Install Gradle and put it on PATH. Any recent version works; the build does" >&2
      echo "     not depend on the exact one." >&2
      echo "       Debian/Ubuntu:  sudo apt install gradle" >&2
      echo "       macOS:          brew install gradle" >&2
      echo >&2
      echo "  2. Download the distribution elsewhere and point at the file:" >&2
      echo "       NIIX_GRADLE_ZIP=/path/to/gradle-8.14.4-bin.zip ./build-niix.sh" >&2
      echo >&2
      echo "  3. Extract a Gradle distribution to $TOOLCHAIN/gradle" >&2
      echo "     (so that $TOOLCHAIN/gradle/bin/gradle exists) and re-run." >&2
      echo >&2
      echo "The Android SDK can be supplied the same way: set ANDROID_HOME to an existing SDK," >&2
      echo "or NIIX_CMDLINE_TOOLS_ZIP to a downloaded command-line tools archive. A JDK 17+ on" >&2
      echo "PATH or JAVA_HOME is used as-is." >&2
      exit 1
    fi
    local gradle_sha="${NIIX_GRADLE_SHA256:-}"
    if [ -z "$gradle_sha" ]; then
      gradle_sha=$(curl -fsSL "${dist_url}.sha256" 2>/dev/null | tr -d ' \n' || true)
    fi
    if [ -z "$gradle_sha" ]; then
      # Fail closed: this archive is about to be executed, so running it unverified means
      # trusting whatever the network returned.
      echo "error: could not obtain a checksum for the Gradle distribution, and this script" >&2
      echo "       will not execute an unverified one. Set NIIX_GRADLE_SHA256 to the hash" >&2
      echo "       published at ${dist_url}.sha256 and re-run." >&2
      rm -f "$TOOLCHAIN/gradle.zip"
      exit 1
    fi
    verify_sha256 "$TOOLCHAIN/gradle.zip" "$gradle_sha" "Gradle distribution"

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
  # As with Gradle, a locally supplied archive is accepted so this works without reaching
  # Google's servers.
  if [ -n "${NIIX_CMDLINE_TOOLS_ZIP:-}" ] && [ -f "$NIIX_CMDLINE_TOOLS_ZIP" ]; then
    cp "$NIIX_CMDLINE_TOOLS_ZIP" "$TOOLCHAIN/cmdline-tools.zip"
    echo "==> Using the command-line tools supplied at $NIIX_CMDLINE_TOOLS_ZIP"
  else
  local zip_url="https://dl.google.com/android/repository/commandlinetools-${OS}-11076708_latest.zip"
  if ! curl -fsSL "$zip_url" -o "$TOOLCHAIN/cmdline-tools.zip"; then
    echo "error: Android SDK command-line tools download failed (tried $zip_url)." >&2
    echo "       Get a current download link from" >&2
    echo "       https://developer.android.com/studio#command-line-tools-only" >&2
    echo "       unzip it into $sdk_dir/cmdline-tools/latest (so that path contains bin/, lib/," >&2
    echo "       etc. directly), then re-run this script." >&2
    exit 1
  fi
  fi
  verify_sha256 "$TOOLCHAIN/cmdline-tools.zip" "${NIIX_CMDLINE_TOOLS_SHA256:-}" "Android command-line tools"

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

  # apksigner is a shell wrapper that execs `java`. This script manages its own JDK and points
  # Gradle at it via JAVA_HOME, but never put it on PATH -- so apksigner failed with
  # "exec: java: not found" on any machine without a system java, and the message below then
  # blamed the APK for what was a missing interpreter. Give the wrapper the same JDK Gradle used.
  local apksigner_java="${JAVA_HOME:-}"
  if [ -z "$apksigner_java" ] && [ -x "$TOOLCHAIN/jdk/bin/java" ]; then
    apksigner_java="$TOOLCHAIN/jdk"
  fi

  local verify_output verify_rc
  verify_output=$(JAVA_HOME="$apksigner_java" PATH="$apksigner_java/bin:$PATH" \
    "$apksigner" verify "$apk_path" 2>&1) && verify_rc=0 || verify_rc=$?
  printf '%s\n' "$verify_output"

  # Distinguish "apksigner could not run" from "apksigner examined the APK and rejected it".
  # Conflating them sends you looking for a corrupt build when the real problem is the toolchain.
  if [ "$verify_rc" -ne 0 ] && printf '%s' "$verify_output" | grep -q "java: not found"; then
    echo "error: apksigner could not run because no Java was found on PATH." >&2
    echo "       This says nothing about the APK -- it was not examined." >&2
    echo "       Expected a JDK at $TOOLCHAIN/jdk; re-run the build to reinstall the toolchain," >&2
    echo "       or set JAVA_HOME to a JDK 17 or newer." >&2
    exit 1
  fi

  if [ "$verify_rc" -ne 0 ]; then
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
  verify)
    # Tests first: a failing test says more about whether the code is sound than a successful
    # build does, and running it first means a reviewer sees that result even if they stop
    # reading. The debug APK follows so there is something installable to actually exercise.
    echo "==> Running the test suite..."
    "$GRADLE_CMD" --console=plain test
    echo
    echo "==> Test reports:"
    # Android modules write to reports/tests/<variant>UnitTest/, not reports/tests/test/, so
    # matching only the latter printed nothing and left the reviewer with no idea where the
    # results were.
    find . -path "*/build/reports/tests/*/index.html" 2>/dev/null | sed 's/^/    /'
    echo
    echo "==> Building a debug APK (no signing key needed)..."
    "$GRADLE_CMD" --console=plain :app:assembleDebug
    OUT=$(find app/build/outputs/apk/debug -name "*.apk" ! -name "niix-messenger-*" | head -1)
    if [ -n "$OUT" ]; then
      OUT="$(rename_output_apk "$OUT")"
      verify_apk "$OUT"
    fi
    ;;

  test)
    # Runs the automated test suite. Everything it needs -- JDK, Gradle, the wrapper jar, the
    # Android SDK -- is set up first by the same code path a build uses, so this works on a
    # fresh checkout with nothing installed. The wrapper jar in particular is not committed
    # (it is a binary, and committing binaries to a source repo is exactly the kind of thing a
    # reviewer should object to), so running ./gradlew directly on a clean clone fails until it
    # has been regenerated. This does that regeneration as a side effect.
    echo "==> Running the test suite..."
    "$GRADLE_CMD" --console=plain test
    echo
    echo "==> Test reports (open in a browser for details):"
    # Android modules write to reports/tests/<variant>UnitTest/, not reports/tests/test/, so
    # matching only the latter printed nothing and left the reviewer with no idea where the
    # results were.
    find . -path "*/build/reports/tests/*/index.html" 2>/dev/null | sed 's/^/    /'
    exit 0
    ;;

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
