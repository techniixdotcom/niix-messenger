#!/usr/bin/env bash
# build-niix.sh -- the one entry point for building (and optionally publishing) a release.
#
# Run it with no arguments for an interactive menu, or use one of these directly:
#
#   ./build-niix.sh --release              Build a signed release APK using keystore.properties
#                                           (or $NIIX_KEYSTORE_PROPERTIES if set).
#   ./build-niix.sh --publish              Build a signed release APK, then sign it for update
#                                           checks and publish it to GitHub Releases in one go --
#                                           the APK, its .sig, and SHA256SUMS.txt always go up
#                                           together, so it's not possible to publish a release
#                                           the app's own update checker will then refuse to
#                                           offer because the .sig is missing.
#   ./build-niix.sh --upload-only          Publish an already-built release APK (skips building
#                                           entirely -- doesn't even need a JDK or Android SDK).
#   ./build-niix.sh --verify-reproducible  Build an *unsigned* release APK from source alone, for
#                                           comparing its hash against someone else's build of the
#                                           same commit -- never touches a real signing key, even
#                                           if one is configured.
#   ./build-niix.sh --debug                Build an installable debug APK. No signing key needed
#                                           -- Android's own auto-generated debug key is used,
#                                           same as any other Android project.
#
# Publishing (--publish / --upload-only) needs the GitHub CLI ('gh', logged in) and the
# update-signing private key from README.md -- set NIIX_UPDATE_SIGNING_KEY to its path if it's
# not just called update-signing-key.pem in the project root. Set NIIX_RELEASE_TAG to skip the
# "what tag?" prompt.
#
#   NIIX_VERSION_NAME=1.2.0 NIIX_VERSION_CODE=5 ./build-niix.sh --release
#                                           Override version name/code for this build.
#
# This script also makes sure a JDK, Gradle, and the Android SDK actually exist before trying to
# build anything (skipped entirely for --upload-only, which doesn't build). If your machine
# already has them (JAVA_HOME / a system `gradle` / an existing Android SDK), those are used
# as-is. Anything missing is downloaded into ./.toolchain instead of touching anything
# system-wide, so this never needs sudo and never conflicts with an existing install.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
ROOT="$(pwd)"
TOOLCHAIN="$ROOT/.toolchain"
mkdir -p "$TOOLCHAIN"

LOG_FILE="$ROOT/build.log"
exec > >(tee "$LOG_FILE") 2>&1

on_exit() {
  local code=$?
  echo
  echo "Full output of this run was saved to: $LOG_FILE"
  if [ $code -ne 0 ]; then
    echo "This run failed (exit $code) -- that file has everything, including the parts that"
    echo "scrolled by. Send it along if you need help figuring out why."
  fi
  exit $code
}
trap on_exit EXIT

MODE="release"
DO_PUBLISH=false

show_menu() {
  echo "What would you like to do?"
  echo "  1) Build a signed release APK"
  echo "  2) Build a signed release APK and publish it to GitHub Releases"
  echo "  3) Publish an already-built release APK to GitHub Releases"
  echo "  4) Build a debug APK (no signing key needed)"
  echo "  5) Build an unsigned APK for reproducible-build verification"
  local choice
  read -rp "Enter a number [1-5]: " choice
  case "$choice" in
    1) MODE="release" ;;
    2) MODE="release"; DO_PUBLISH=true ;;
    3) MODE="upload-only" ;;
    4) MODE="debug" ;;
    5) MODE="reproducible" ;;
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
      --publish) MODE="release"; DO_PUBLISH=true ;;
      --upload-only) MODE="upload-only" ;;
      -h|--help)
        sed -n '2,32p' "${BASH_SOURCE[0]}"
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
  yes | "$sdkmanager" --sdk_root="$sdk_dir" --licenses >/dev/null 2>&1 || true
  "$sdkmanager" --sdk_root="$sdk_dir" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

  echo "sdk.dir=$sdk_dir" > local.properties
  echo "==> Android SDK ready at $sdk_dir"
}

find_repo_slug() {
  sed -n 's/.*repoOwnerSlashName: String = "\([^"]*\)".*/\1/p' \
    app/src/main/java/app/niix/update/UpdateChecker.kt
}

read_version_name() {
  sed -n 's/^versionName=\(.*\)/\1/p' version.properties
}

read_version_code() {
  sed -n 's/^versionCode=\(.*\)/\1/p' version.properties
}

# Asks what version this release is, suggesting the next patch version as a default so you can
# just hit enter most of the time -- never silently picks a version on its own. versionCode
# always goes up by at least 1 from whatever it last was, regardless of what versionName is
# typed, since Android requires a strictly increasing versionCode for an update to install at
# all (this exact gap -- versionCode silently defaulting to 1 forever -- is why "app not
# installed as package appears to be invalid" happened: every build looked identical to Android).
bump_version() {
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
    read -rp "Version name for this release (currently $old_name) [$suggested_name]: " new_name
    new_name="${new_name:-$suggested_name}"
  fi

  new_code="${NIIX_VERSION_CODE:-}"
  if [ -z "$new_code" ]; then
    local suggested_code=$((old_code + 1))
    read -rp "Version code for this release (currently $old_code) [$suggested_code]: " new_code
    new_code="${new_code:-$suggested_code}"
  fi
  if ! [[ "$new_code" =~ ^[0-9]+$ ]]; then
    echo "error: version code must be a whole number, got '$new_code'." >&2
    exit 1
  fi
  if [ "$new_code" -le "$old_code" ]; then
    echo "error: version code $new_code is not greater than the current $old_code --" >&2
    echo "       Android will refuse to install this as an update over what's already there." >&2
    exit 1
  fi

  {
    echo "versionName=$new_name"
    echo "versionCode=$new_code"
  } > version.properties
  echo "==> Version set: $old_name ($old_code) -> $new_name ($new_code)"
  export NIIX_VERSION_NAME="$new_name"
  export NIIX_VERSION_CODE="$new_code"
  NEW_VERSION_NAME="$new_name"
}

# Renames the just-built APK to niix-messenger-<versionName>.apk as a copy alongside Gradle's
# own output, leaving Gradle's own file where it expects it (so incremental builds aren't
# confused by us moving what it thinks it owns).
rename_output_apk() {
  local original="$1"
  local version_name
  version_name="$(read_version_name)"
  version_name="${version_name:-0.1.0}"
  local renamed
  renamed="$(dirname "$original")/niix-messenger-${version_name}.apk"
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
    echo "       This means the build itself produced a broken APK -- nothing downstream" >&2
    echo "       (signing for updates, uploading, downloading) can fix that. Try a clean" >&2
    echo "       build: rm -rf app/build && ./build-niix.sh, and if it still fails, that's" >&2
    echo "       worth investigating as its own problem rather than a network/Tor issue." >&2
    exit 1
  fi
  echo "==> apksigner confirms this APK is structurally valid and correctly signed."
}

ensure_gh() {
  if ! command -v gh >/dev/null 2>&1; then
    echo "error: the GitHub CLI ('gh') isn't installed -- needed to publish a release." >&2
    echo "       Debian/Ubuntu: sudo apt install gh" >&2
    echo "       macOS:         brew install gh" >&2
    echo "       Anything else: https://github.com/cli/cli#installation" >&2
    exit 1
  fi
  if ! gh auth status >/dev/null 2>&1; then
    echo "error: 'gh' is installed but not logged in. Run 'gh auth login' once, then re-run this." >&2
    exit 1
  fi
}

publish_release() {
  local apk_path="$1"
  local known_version_name="${2:-}"
  ensure_gh

  local key="${NIIX_UPDATE_SIGNING_KEY:-}"
  if [ -z "$key" ]; then
    for candidate in update-signing-key.pem niix-update-signing.pem update-signing.pem; do
      if [ -f "$candidate" ]; then
        key="$candidate"
        break
      fi
    done
  fi
  key="${key:-update-signing-key.pem}"
  if [ ! -f "$key" ]; then
    echo "error: no update-signing key found." >&2
    echo "       Looked for update-signing-key.pem, niix-update-signing.pem, and" >&2
    echo "       update-signing.pem in the project root -- none of those exist." >&2
    echo "       Set NIIX_UPDATE_SIGNING_KEY to its actual path, or see README.md to generate" >&2
    echo "       one. This is a separate key from your APK signing keystore -- don't point" >&2
    echo "       this at niix-release.jks." >&2
    exit 1
  fi

  echo "==> Checking $key matches the public key the app was built with..."
  local derived_hex compiled_hex
  derived_hex=$(openssl pkey -in "$key" -pubout -outform DER 2>/dev/null | tail -c 32 | od -An -tx1 | tr -d ' \n')
  compiled_hex=$(sed -n '/RELEASE_SIGNING_PUBLIC_KEY = byteArrayOf(/,/^        )/p' \
    app/src/main/java/app/niix/update/UpdateChecker.kt | grep -oE '0x[0-9a-fA-F]{2}' | sed 's/^0x//' | tr -d '\n' | tr '[:upper:]' '[:lower:]')
  if [ -z "$derived_hex" ] || [ ${#derived_hex} -ne 64 ]; then
    echo "error: couldn't read an Ed25519 public key from '$key'." >&2
    echo "       Is it really an Ed25519 private key in PEM/PKCS8 format" >&2
    echo "       (openssl genpkey -algorithm ed25519 -out ...)?" >&2
    exit 1
  fi
  if [ "$derived_hex" != "$compiled_hex" ]; then
    echo "error: '$key' does not match RELEASE_SIGNING_PUBLIC_KEY in UpdateChecker.kt." >&2
    echo "       Signing with this key would publish a release the app itself will refuse to" >&2
    echo "       treat as an update, since it verifies against a different key -- it isn't a" >&2
    echo "       filename problem, it's the wrong key. Use whichever private key's public half" >&2
    echo "       is actually baked into the app, or update UpdateChecker.kt to match this one" >&2
    echo "       and rebuild first." >&2
    exit 1
  fi
  echo "==> Confirmed: this key's public half matches what's compiled into the app."

  local repo_slug
  repo_slug="$(find_repo_slug)"
  if [ -z "$repo_slug" ] || [[ "$repo_slug" == *YOUR_GITHUB_USER* ]]; then
    echo "error: repoOwnerSlashName in UpdateChecker.kt isn't set to a real repo yet." >&2
    exit 1
  fi

  local apk_dir apk_name sig_path sums_path
  apk_dir="$(cd "$(dirname "$apk_path")" && pwd)"
  apk_name="$(basename "$apk_path")"
  sig_path="$apk_dir/$apk_name.sig"
  sums_path="$apk_dir/SHA256SUMS.txt"

  echo "==> Signing $apk_name for update verification..."
  openssl pkeyutl -sign -inkey "$key" -rawin -in "$apk_path" -out "$sig_path"

  echo "==> Computing checksums..."
  (cd "$apk_dir" && sha256sum "$apk_name" > SHA256SUMS.txt)

  local tag="${NIIX_RELEASE_TAG:-}"
  if [ -z "$tag" ] && [ -n "$known_version_name" ]; then
    tag="v$known_version_name"
  fi
  if [ -z "$tag" ]; then
    local suggested
    suggested="v$(read_version_name)"
    read -rp "Release tag (this must match the version actually built into that APK) [$suggested]: " tag
    tag="${tag:-$suggested}"
  fi
  if [ -z "$tag" ]; then
    echo "error: no tag given." >&2
    exit 1
  fi

  echo "==> Replacing the source on $repo_slug with the current tree..."
  sync_source_to_repo "$repo_slug" "$tag"

  echo "==> Publishing $tag to $repo_slug..."
  if gh release view "$tag" --repo "$repo_slug" >/dev/null 2>&1; then
    gh release upload "$tag" "$apk_path" "$sig_path" "$sums_path" --repo "$repo_slug" --clobber
  else
    gh release create "$tag" "$apk_path" "$sig_path" "$sums_path" \
      --repo "$repo_slug" --title "$tag" --generate-notes
  fi
  echo "==> Published: https://github.com/$repo_slug/releases/tag/$tag"
  echo "    (Source replaced, then this release added -- existing releases were left alone.)"
}

# Replaces every file in $repo_slug's default branch with the current project tree: builds a
# secrets-free copy (checked afterward, not just trusted, same as everywhere else this matters),
# commits it as a single fresh commit with no prior history, and force-pushes over whatever was
# there -- "delete the old source code and upload the new one" as a literal git operation, not a
# zip attached to a release. Authenticates via `gh auth setup-git`, which points git's own
# credential helper at the already-logged-in `gh` session rather than needing a token handled
# separately here.
sync_source_to_repo() {
  local repo_slug="$1"
  local tag="$2"

  local stage
  stage="$(mktemp -d)"
  cp -r . "$stage/niix"
  rm -rf \
    "$stage/niix/.git" \
    "$stage/niix/.gradle" \
    "$stage/niix/.toolchain" \
    "$stage/niix/local.properties" \
    "$stage/niix/build.log"
  find "$stage/niix" -type d -name build -prune -exec rm -rf {} +
  find "$stage/niix" -type f \( -name "keystore.properties" -o -name "*.jks" -o -name "*.pem" \) -delete

  local leaked
  leaked=$(find "$stage/niix" \( -name "keystore.properties" -o -name "*.jks" -o -name "*.pem" \
    -o -name "local.properties" -o -name "build.log" -o -path "*/.git/*" -o -path "*/.gradle/*" \
    -o -path "*/.toolchain/*" -o -path "*/build/*" \) 2>/dev/null)
  if [ -n "$leaked" ]; then
    echo "error: the staged source copy still contains excluded files after cleanup --" >&2
    echo "       refusing to push or publish anything. Found:" >&2
    echo "$leaked" >&2
    rm -rf "$stage"
    exit 1
  fi

  local default_branch
  default_branch="$(gh repo view "$repo_slug" --json defaultBranchRef --jq .defaultBranchRef.name 2>/dev/null)"
  default_branch="${default_branch:-main}"

  gh auth setup-git >/dev/null 2>&1 || true

  if ! (
    cd "$stage/niix" &&
    git init -q &&
    git checkout -q -b "$default_branch" &&
    git add -A &&
    git -c user.email="niix-release@localhost" -c user.name="niix-release" commit -q -m "$tag" &&
    git remote add origin "https://github.com/$repo_slug.git" &&
    git push --force origin "HEAD:$default_branch"
  ); then
    rm -rf "$stage"
    echo "error: pushing the new source to $repo_slug ($default_branch) failed -- see the" >&2
    echo "       output above. Nothing was deleted or published past this point." >&2
    exit 1
  fi
  rm -rf "$stage"
  echo "==> Source replaced on $repo_slug ($default_branch)."
}

if [ "$MODE" != "upload-only" ]; then
  echo "==> Checking build tools..."
  ensure_jdk
  ensure_gradle
  ensure_android_sdk
fi

case "$MODE" in
  debug)
    echo "==> Building debug APK (no signing key required)..."
    "$GRADLE_CMD" --console=plain :app:assembleDebug
    OUT=$(find app/build/outputs/apk/debug -name "*.apk" ! -name "niix-messenger-*" | head -1)
    [ -n "$OUT" ] && OUT="$(rename_output_apk "$OUT")"
    [ -n "$OUT" ] && verify_apk "$OUT"
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

See README.md for the full walkthrough, including the release-signing keystore *and*
the separate update-signing keypair (different key, different purpose).

To build something installable right now without any of that, run:
  ./build-niix.sh --debug
EOF
      exit 1
    fi

    NEW_VERSION_NAME=""
    if [ "$DO_PUBLISH" = true ]; then
      bump_version
    fi

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
    if [ "$DO_PUBLISH" = true ] && [ -n "$OUT" ]; then
      publish_release "$OUT" "$NEW_VERSION_NAME"
    fi
    ;;

  upload-only)
    OUT=$(find app/build/outputs/apk/release -name "niix-messenger-*.apk" | head -1)
    if [ -z "$OUT" ]; then
      OUT=$(find app/build/outputs/apk/release -name "*.apk" ! -name "*unsigned*" | head -1)
    fi
    if [ -z "$OUT" ]; then
      echo "error: no previously-built release APK found in app/build/outputs/apk/release/." >&2
      echo "       Build one first (./build-niix.sh --release), then publish." >&2
      exit 1
    fi
    publish_release "$OUT"
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
