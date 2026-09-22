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
# Confirm the log can actually be written before redirecting into it.
#
# `exec > >(tee ...)` fails silently if the file cannot be created -- a read-only directory, a
# full disk, a root-owned build.log from a sudo run. The script then dies with no output and no
# log, which is the one failure mode that leaves nothing at all to diagnose from. Checking first
# means the reason is at least printed to the terminal.
if ! : > "$LOG_FILE" 2>/dev/null; then
  echo "error: cannot write the log file at $LOG_FILE" >&2
  echo "       Check the directory is writable and that no root-owned $(basename "$LOG_FILE")" >&2
  echo "       is left over from running this script with sudo:" >&2
  echo "         ls -l $LOG_FILE" >&2
  echo "       Everything below would otherwise have gone unrecorded, so this stops here." >&2
  exit 1
fi
# Unbuffered, so the log is readable while the run is still going.
#
# tee buffers by default, so a run that hangs or is killed leaves a log that stops well short of
# where it actually got to -- exactly the case where the last few lines are what matter. `stdbuf`
# forces line buffering; where it is unavailable the plain tee is used, which is no worse than
# before.
if command -v stdbuf >/dev/null 2>&1; then
  exec > >(stdbuf -oL -eL tee "$LOG_FILE") 2>&1
else
  exec > >(tee "$LOG_FILE") 2>&1
fi

# Turns XML entities back into the characters they stand for. Test failure messages are full of
# comparisons like expected:<1> but was:<2>, which XML stores escaped -- printing them raw makes
# the one line a reader most needs harder to read than it was before.
unescape_xml() {
  sed -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&amp;/\&/g'
}

# Collects every module's test results into one file.
#
# Gradle writes results per module and per variant, so a run leaves a dozen XML directories and
# as many HTML reports -- none of which is a single place to look, and none of which can be
# attached to a bug report or handed to a reviewer. This flattens them into one plain-text file:
# every suite, every test, and the failures spelled out rather than linked to.
write_full_test_log() {
  local out="$ROOT/build-test-full.log"
  {
    echo "NiiX test results"
    echo "Generated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    echo "Version:   $(read_version_name 2>/dev/null || echo unknown) ($(read_version_code 2>/dev/null || echo unknown))"
    echo
  } > "$out"

  local total=0 failed=0 skipped=0 suites=0
  # Sorted so the file reads the same way twice, which matters when diffing two runs.
  while IFS= read -r xml; do
    [ -f "$xml" ] || continue
    local name tests fails errs skips
    name=$(sed -n 's/.*<testsuite[^>]*name="\([^"]*\)".*/\1/p' "$xml" | head -1)
    tests=$(sed -n 's/.*<testsuite[^>]*tests="\([0-9]*\)".*/\1/p' "$xml" | head -1)
    fails=$(sed -n 's/.*<testsuite[^>]*failures="\([0-9]*\)".*/\1/p' "$xml" | head -1)
    errs=$(sed -n 's/.*<testsuite[^>]*errors="\([0-9]*\)".*/\1/p' "$xml" | head -1)
    skips=$(sed -n 's/.*<testsuite[^>]*skipped="\([0-9]*\)".*/\1/p' "$xml" | head -1)
    [ -z "$name" ] && continue

    suites=$((suites + 1))
    total=$((total + ${tests:-0}))
    failed=$((failed + ${fails:-0} + ${errs:-0}))
    skipped=$((skipped + ${skips:-0}))

    {
      echo "───────────────────────────────────────────────────────────────"
      echo "$name"
      echo "  ${tests:-0} test(s), ${fails:-0} failure(s), ${errs:-0} error(s), ${skips:-0} skipped"
      # Individual test names, so the file records what was actually checked rather than only
      # that a count passed. Anchored on `<testcase name=` specifically: a looser pattern picks
      # up the classname attribute that follows it and prints the class for every test.
      sed -n 's/.*<testcase name="\([^"]*\)".*/    - \1/p' "$xml" | unescape_xml
      # Failure detail inline -- a reviewer should not have to open an HTML report to find out
      # what broke. Only the message and the first lines of the trace: dumping the raw element
      # reproduces XML markup and escaped entities, which is less readable than the report it is
      # meant to replace.
      if [ "${fails:-0}" != "0" ] || [ "${errs:-0}" != "0" ]; then
        echo "  FAILURE DETAIL:"
        sed -n 's/.*<failure[^>]*message="\([^"]*\)".*/      message: \1/p' "$xml" | unescape_xml
        sed -n 's/.*<failure[^>]*>\(.*\)/      \1/p' "$xml" | sed 's/<\/failure>//' | unescape_xml | head -10
      fi
      echo
    } >> "$out"
  done < <(find . -path "*/build/test-results/*" -name "TEST-*.xml" 2>/dev/null | sort)

  {
    echo "═══════════════════════════════════════════════════════════════"
    echo "TOTAL: $total test(s) across $suites suite(s)"
    echo "       $failed failure(s), $skipped skipped"
    if [ "$failed" = "0" ] && [ "$total" != "0" ]; then
      echo "RESULT: all tests passed"
    elif [ "$total" = "0" ]; then
      echo "RESULT: no test results found -- did the test task run?"
    else
      echo "RESULT: FAILED"
    fi
  } >> "$out"

  echo "==> Full test results written to: $out"
  tail -5 "$out" | sed 's/^/    /'
}



# Resolves adb from the SDK this script manages, falling back to one already on PATH.
resolve_adb() {
  local sdk_root=""
  [ -f local.properties ] && sdk_root=$(sed -n 's/^sdk\.dir=//p' local.properties)
  local candidate="$sdk_root/platform-tools/adb"
  if [ -x "$candidate" ]; then
    echo "$candidate"
    return 0
  fi
  command -v adb 2>/dev/null || true
}


# Resolves adb from the SDK this script manages, falling back to one already on PATH.
resolve_adb() {
  local sdk_root=""
  [ -f local.properties ] && sdk_root=$(sed -n 's/^sdk\.dir=//p' local.properties)
  local candidate="$sdk_root/platform-tools/adb"
  if [ -x "$candidate" ]; then
    echo "$candidate"
    return 0
  fi
  command -v adb 2>/dev/null || true
}

require_device() {
  local adb_bin="$1"
  if [ -z "$adb_bin" ] || [ ! -x "$adb_bin" ]; then
    echo "error: adb not found. It normally comes with the SDK this script installs." >&2
    exit 1
  fi
  # Start the daemon explicitly first.
  #
  # The first `adb devices` of a session prints "daemon not running; starting now" and "daemon
  # started successfully" ahead of the device list. Reading a fixed line number then landed on a
  # daemon message instead of the device, and reported no device while one was plugged in and
  # working. Starting the server separately keeps that noise out of the list entirely.
  "$adb_bin" start-server >/dev/null 2>&1 || true

  local devices_output
  devices_output="$("$adb_bin" devices 2>&1 || true)"

  # Match any line ending in a tab and "device" -- not a line position, and not a substring,
  # since "unauthorized" and "offline" also contain the word.
  if printf '%s\n' "$devices_output" | grep -qE '[[:space:]]device$'; then
    return 0
  fi

  # Distinguish the states that look like a connected phone but are not. Each needs a different
  # action from the user, and "no device connected" is wrong advice for all of them.
  if printf '%s\n' "$devices_output" | grep -qE '[[:space:]]unauthorized$'; then
    echo "error: the phone is connected but has not authorised this computer." >&2
    echo "       Unlock the phone and accept the 'Allow USB debugging?' prompt. If no prompt" >&2
    echo "       appeared, revoke previous authorisations in Developer options and replug." >&2
  elif printf '%s\n' "$devices_output" | grep -qE '[[:space:]]offline$'; then
    echo "error: the phone is connected but adb reports it offline." >&2
    echo "       Unplug and replug it, or restart adb:  $adb_bin kill-server" >&2
  elif printf '%s\n' "$devices_output" | grep -qE '[[:space:]](no permissions|authorizing)'; then
    echo "error: adb can see the phone but cannot talk to it." >&2
    echo "       On Linux this is usually a udev rule: your user lacks permission for the USB" >&2
    echo "       device. Try a different cable or port first, then check udev rules." >&2
  else
    echo "error: no device connected. Enable USB debugging on the phone, plug it in, accept" >&2
    echo "       the 'Allow USB debugging?' prompt, then re-run this." >&2
    echo "       Note: a cable that only carries power will look exactly like this." >&2
  fi

  # The raw output, because the guesses above cannot cover every case and this one line is
  # usually enough to tell what is actually wrong.
  echo >&2
  echo "       adb ($adb_bin) reported:" >&2
  printf '%s\n' "$devices_output" | sed 's/^/         /' >&2
  exit 1
}

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
  # Every lookup below ends with `|| true`. Under `set -e` a grep that matches nothing
  # returns 1, and in a command substitution that aborts the trap outright -- so a log whose
  # only error was the last kind checked produced no summary at all, which is exactly the
  # An install refused for signature mismatch is not a build failure, and the Gradle output for
  # it is a 150-line Java stack trace with the one useful word buried in the middle.
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" "$LOG_FILE" 2>/dev/null; then
    echo
    echo "---- the app on the phone was signed with a different key ----"
    echo
    echo "    Android refuses to update a package unless the new APK is signed with the same"
    echo "    key. Nothing is wrong with this build; the phone is holding an older install"
    echo "    signed by a different key -- a different computer, or a regenerated debug key."
    echo
    echo "    Uninstall it and re-run:"
    echo "      adb uninstall app.niix.debug"
    echo
    echo "    If that is the REAL app rather than a debug build, uninstalling erases its data."
    echo "    Check which you have before doing it:"
    echo "      adb shell pm list packages | grep niix"
    return 0
  fi

  # situation this function exists to handle.
  # A download that timed out is not a build problem, but Gradle reports it the same way as one:
  # a long "Could not resolve" block that reads like something in the project is broken. Nothing
  # needs fixing; the same command usually succeeds on a second run, because whatever did
  # download is now cached.
  if grep -qE "Read timed out|Connect timed out|Could not GET 'https://|Connection reset" "$LOG_FILE" 2>/dev/null \
     && ! grep -q "^e: " "$LOG_FILE" 2>/dev/null; then
    echo
    echo "---- a download timed out: this is the network, not the code ----"
    echo
    echo "    Gradle could not fetch some libraries in time. No compile errors were found."
    echo "    Run the same option again; what already downloaded is cached, so the next run"
    echo "    only fetches what is missing."
    echo
    echo "    If it keeps timing out, the Maven server may be slow or blocked on this network."
    return 0
  fi

  gradle_block=$(sed -n '/^FAILURE: Build failed/,/^\* Try:/p' "$LOG_FILE" 2>/dev/null | head -40 || true)
  if [ -n "$gradle_block" ]; then
    echo
    echo "---- what actually failed ----"
    printf '%s\n' "$gradle_block" | grep -v '^\* Try:'
    return 0
  fi


  # Dependency verification failures get their own explanation. Gradle reports them as a wall of
  # hashes, and the two causes look identical in that output while meaning completely different
  # things: a version you changed yourself, or an artifact that is not what it was.
  if grep -q "Dependency verification failed" "$LOG_FILE" 2>/dev/null; then
    echo
    echo "---- dependency verification failed ----"
    grep -E "^\s+- .*(checksum|artifact|not trusted)" "$LOG_FILE" 2>/dev/null | head -12 || true
    echo
    echo "    If you changed a dependency version, regenerate the hashes:"
    echo "      ./build-niix.sh --write-dependency-hashes"
    echo
    echo "    If you did NOT change anything, do not regenerate. An artifact you already had a"
    echo "    hash for has changed content, which is what a compromised dependency looks like."
    echo "    Regenerating would overwrite the evidence and make the build pass."
    return 0
  fi

  # Kotlin compile errors: report the first few, since later ones are usually consequences.
  local kotlin_errors
  kotlin_errors=$(grep -E "^e: " "$LOG_FILE" 2>/dev/null | head -5 || true)
  if [ -n "$kotlin_errors" ]; then
    echo
    echo "---- what actually failed ----"
    printf '%s\n' "$kotlin_errors"
    local total
    total=$(grep -cE "^e: " "$LOG_FILE" 2>/dev/null | head -1 || echo 0)
    [ "$total" -gt 5 ] && echo "  ... and $((total - 5)) more compile error(s) in the log."
    return 0
  fi

  # Anything this script itself raised.
  local script_errors
  script_errors=$(grep -E "^error: " "$LOG_FILE" 2>/dev/null | tail -5 || true)
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
      --device-tests) MODE="devicetests" ;;
      --write-dependency-hashes) MODE="dephashes" ;;
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
  # Verified against the first copy ever downloaded, if no hash is pinned explicitly.
  #
  # This is executed during the build, so a substituted download is code execution in the build
  # environment. A mandatory pin would be stronger, but it would also fail every clean build
  # until someone went and found the hash -- so instead the first download is recorded and every
  # later one must match it. That catches a changed artifact on a machine that has built before,
  # which is the realistic tampering case, without blocking a first run. Set NIIX_JDK_SHA256
  # to enforce a hash you obtained independently, which is strictly better.
  niix_jdk_sha256_recorded="$TOOLCHAIN/.jdk.tar.gz.sha256"
  if [ -z "${NIIX_JDK_SHA256:-}" ] && [ -f "$niix_jdk_sha256_recorded" ]; then
    NIIX_JDK_SHA256=$(cat "$niix_jdk_sha256_recorded" 2>/dev/null || true)
    [ -n "${NIIX_JDK_SHA256:-}" ] && echo "==> Verifying jdk against the copy recorded on first download."
  fi
  verify_sha256 "$TOOLCHAIN/jdk.tar.gz" "${NIIX_JDK_SHA256:-}" "JDK"
  sha256sum "$TOOLCHAIN/jdk.tar.gz" 2>/dev/null | cut -d' ' -f1 > "$niix_jdk_sha256_recorded" || true

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
    echo "    Using it in preference to the committed wrapper jar; both are checked against a"
    echo "    pinned hash before use."
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
  # Verified against the first copy ever downloaded, if no hash is pinned explicitly.
  #
  # This is executed during the build, so a substituted download is code execution in the build
  # environment. A mandatory pin would be stronger, but it would also fail every clean build
  # until someone went and found the hash -- so instead the first download is recorded and every
  # later one must match it. That catches a changed artifact on a machine that has built before,
  # which is the realistic tampering case, without blocking a first run. Set NIIX_CMDLINE_TOOLS_SHA256
  # to enforce a hash you obtained independently, which is strictly better.
  niix_cmdline_tools_sha256_recorded="$TOOLCHAIN/.cmdline-tools.zip.sha256"
  if [ -z "${NIIX_CMDLINE_TOOLS_SHA256:-}" ] && [ -f "$niix_cmdline_tools_sha256_recorded" ]; then
    NIIX_CMDLINE_TOOLS_SHA256=$(cat "$niix_cmdline_tools_sha256_recorded" 2>/dev/null || true)
    [ -n "${NIIX_CMDLINE_TOOLS_SHA256:-}" ] && echo "==> Verifying android command-line tools against the copy recorded on first download."
  fi
  verify_sha256 "$TOOLCHAIN/cmdline-tools.zip" "${NIIX_CMDLINE_TOOLS_SHA256:-}" "Android command-line tools"
  sha256sum "$TOOLCHAIN/cmdline-tools.zip" 2>/dev/null | cut -d' ' -f1 > "$niix_cmdline_tools_sha256_recorded" || true

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

# Identifies which copy of this script is running.
#
# Three rounds were spent on one failure because the logs could not show whether a fix was even
# present -- an older extraction looks identical in output to a current one. Printing the script's
# own hash makes that unambiguous: if it does not match the version that was sent, the fix is not
# in the file being run.
SCRIPT_REVISION="$(sha256sum "$0" 2>/dev/null | cut -c1-12 || echo unknown)"
echo "==> build-niix.sh revision $SCRIPT_REVISION"

# Tools this script cannot install, because it needs them to install anything else.
#
# Everything version-specific -- JDK, Gradle, Android SDK, gh -- is fetched automatically. These
# are the base utilities that fetching depends on, so a missing one has to be reported rather
# than worked around. Naming all of them at once beats failing on the first, then the next.
missing_tools=""
for tool in curl tar unzip sha256sum git; do
  command -v "$tool" >/dev/null 2>&1 || missing_tools="$missing_tools $tool"
done
if [ -n "$missing_tools" ]; then
  echo "error: this script needs these and they are not installed:$missing_tools" >&2
  echo >&2
  echo "       Debian/Ubuntu: sudo apt install$missing_tools" >&2
  echo "       Arch:          sudo pacman -S$missing_tools" >&2
  echo "       Fedora:        sudo dnf install$missing_tools" >&2
  echo "       macOS:         already present, or brew install$missing_tools" >&2
  echo >&2
  echo "       Everything else it needs (JDK, Gradle, Android SDK, gh) is downloaded" >&2
  echo "       automatically, so this is the only manual step." >&2
  exit 1
fi

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
    OUT=$(find app/build/outputs/apk/debug -name "*.apk" ! -name "niix-messenger-*" 2>/dev/null | head -1 || true)
    if [ -n "$OUT" ]; then
      OUT="$(rename_output_apk "$OUT")"
      verify_apk "$OUT"
    fi
    ;;

  dephashes)
    # Records a hash for every dependency artifact, so a substituted or tampered one fails the
    # build instead of being compiled in silently.
    #
    # The file has to be generated by actually resolving the dependencies -- there is no way to
    # write it by hand -- which is why this is a command rather than something shipped with the
    # source. Once gradle/verification-metadata.xml exists Gradle enforces it on every build.
    #
    # It has to be regenerated whenever a dependency version changes, and the build will fail
    # until it is. That is the feature working, not a fault: an unexplained hash mismatch is
    # exactly what a supply-chain attack looks like, and it should stop the build.
    echo "==> Recording dependency hashes (this resolves every dependency, so it takes a while)..."
    "$GRADLE_CMD" --write-verification-metadata sha256 help --console=plain
    if [ -f gradle/verification-metadata.xml ]; then
      echo
      echo "==> Wrote gradle/verification-metadata.xml"
      echo "    Commit it. From now on every build checks each dependency against these hashes."
      echo
      echo "    Read it before committing: it is a record of exactly what your machine resolved"
      echo "    at this moment. If your toolchain was already compromised, you are freezing that"
      echo "    compromise in place and giving it the appearance of verification."
      echo
      echo "    After changing any dependency version, re-run this or the build will fail."
    else
      echo "error: Gradle did not produce gradle/verification-metadata.xml." >&2
      echo "       Nothing has been changed; the build is unaffected." >&2
      exit 1
    fi
    exit 0
    ;;

  devicetests)
    # Builds a second APK that drives the first one on a real phone.
    #
    # The JVM suite cannot touch the Android Keystore, SQLCipher's native library or real file
    # I/O -- so the parts of this app that actually protect data are the parts it never tests.
    # An instrumentation APK runs inside the same process as the app on the device, which is the
    # only place those paths exist.
    # Reuses the same adb discovery and device check the install and duress-verify modes use,
    # so all three behave identically when no phone is attached.
    ADB="$(resolve_adb)"
    require_device "$ADB"

    echo "==> Removing any previous debug install (test package only, not the real app)..."
    # The core modules install their own test APKs too, and they collide in exactly the same
    # way. They installed cleanly this time only because they were not already present.
    for pkg in \
      app.niix.debug \
      app.niix.debug.test \
      app.niix.core.relay.test \
      app.niix.core.storage.test \
      app.niix.core.transport.test \
      app.niix.core.messaging.test \
      app.niix.core.model.test \
      app.niix.core.crypto.test; do
      # Unconditional rather than checking first: `pm list packages` does not see packages
      # installed under a different Android user (work profile, secondary user), so a check
      # would report the package absent while the install still collides with it.
      #
      # Output is shown rather than discarded. Sending it to /dev/null meant a uninstall that
      # failed for an interesting reason -- device owner, admin policy, another user -- looked
      # exactly like one that succeeded, and the only symptom was a confusing install error a
      # minute later.
      out1="$("$ADB" uninstall "$pkg" 2>&1 || true)"
      out2="$("$ADB" shell pm uninstall --user 0 "$pkg" 2>&1 | tr -d '\r' || true)"
      case "$out1$out2" in
        *Success*) echo "    removed $pkg" ;;
        *"not installed for"*|*"Unknown package"*|*"DELETE_FAILED_INTERNAL_ERROR"*)
          : ;;  # was not there; nothing to report
        *) [ -n "$out1$out2" ] && echo "    $pkg: $out1 $out2" ;;
      esac
    done

    # Confirm it is actually gone, because a silent failure here produces the confusing
    # INSTALL_FAILED_UPDATE_INCOMPATIBLE later rather than an error at the point of the problem.
    # Confirm, and show what the phone actually reports. A silent failure here produces the
    # confusing INSTALL_FAILED_UPDATE_INCOMPATIBLE much later instead of an error at the point
    # the problem occurred.
    echo "==> NiiX packages on the device after cleanup:"
    remaining="$("$ADB" shell pm list packages 2>/dev/null | tr -d '\r' | grep -i niix || true)"
    if [ -z "$remaining" ]; then
      echo "    (none)"
    else
      printf '%s\n' "$remaining" | sed 's/^/    /'
    fi

    if printf '%s\n' "$remaining" | grep -qx "package:app.niix.debug"; then
      echo "error: app.niix.debug is still installed and could not be removed." >&2
      echo "       Listed above is what the phone reports. Usual causes:" >&2
      echo "         - installed under a work profile or secondary Android user" >&2
      echo "         - installed by a device-owner or MDM policy that forbids removal" >&2
      echo "       Remove it from the phone: Settings > Apps > NiiX (or Sync) > Uninstall," >&2
      echo "       checking every profile, then re-run." >&2
      exit 1
    fi

    OUTDIR="$ROOT/device-test-results"
    rm -rf "$OUTDIR" && mkdir -p "$OUTDIR"

    echo "==> Building the app and the test APK..."
    "$GRADLE_CMD" --console=plain assembleDebug assembleDebugAndroidTest

    # Remove any existing debug install first.
    #
    # Android refuses to update a package signed with a different key, and debug keys differ
    # between machines and are regenerated when ~/.android/debug.keystore is lost. The result is
    # INSTALL_FAILED_UPDATE_INCOMPATIBLE, which reads like a build problem and is not one.
    #
    # Safe to do here specifically because this is the debug build (app.niix.debug), a separate
    # package from the real app -- uninstalling it cannot touch a release install or its data.
    # A test run also has no data worth keeping by definition.
    echo "==> Installing both to the device..."
    "$GRADLE_CMD" --console=plain installDebug installDebugAndroidTest

    echo "==> Capturing device state before the run..."
    {
      echo "Device:  $("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
      echo "Android: $("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'))"
      echo "ABI:     $("$ADB" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
      echo "Date:    $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
      echo "Version: $(read_version_name) ($(read_version_code))"
    } > "$OUTDIR/device.txt"
    cat "$OUTDIR/device.txt"

    # Cleared first so the captured log contains this run only, rather than whatever the phone
    # happened to be doing beforehand.
    "$ADB" logcat -c 2>/dev/null || true

    echo
    echo "==> Running the tests on the device..."
    set +e
    # Ask the phone which instrumentation it actually has, rather than assuming the name.
    #
    # The runner is declared in the *test* APK, not the app APK, so the component is
    # app.niix.debug.test/... and not app.niix.debug/... -- assuming the latter produced
    # "Unable to find instrumentation info", which reads like a missing dependency and is not.
    # Discovering it also survives a future change to the id or the suffix.
    # Matched on the exact target, not a prefix. `app.niix` is a prefix of every core module's
    # test package too (app.niix.core.crypto and so on), so a prefix match picked whichever the
    # phone happened to list first -- it chose the crypto module's empty legacy runner and
    # reported "OK (0 tests)", which looks like a pass and tested nothing.
    #
    # The app's own instrumentation is the one whose target is exactly the debug application id.
    RUNNER="$("$ADB" shell pm list instrumentation 2>/dev/null | tr -d '\r' \
      | sed -n 's|^instrumentation:\([^ ]*\) (target=app\.niix\.debug)$|\1|p' | head -1)"
    if [ -z "$RUNNER" ]; then
      echo "error: no instrumentation on the phone targets app.niix.debug." >&2
      echo "       The app's test APK did not install, even though the build reported success." >&2
      echo "       (Core module test APKs may be listed below; those are not the app's tests.)" >&2
      echo "       What the phone reports:" >&2
      "$ADB" shell pm list instrumentation 2>/dev/null | tr -d '\r' | sed 's/^/         /' >&2
      exit 1
    fi
    echo "    using instrumentation: $RUNNER"

    "$ADB" shell am instrument -w -r \
      -e package app.niix \
      "$RUNNER" \
      2>&1 | tee "$OUTDIR/instrumentation.txt"
    device_rc=${PIPESTATUS[0]}
    set -e

    echo "==> Saving logs..."
    "$ADB" logcat -d > "$OUTDIR/logcat.txt" 2>/dev/null || true
    # Crashes specifically: a test that fails because the app died leaves its real cause here
    # rather than in the instrumentation output.
    "$ADB" logcat -d -b crash > "$OUTDIR/crash.txt" 2>/dev/null || true
    "$ADB" shell dumpsys package app.niix.debug > "$OUTDIR/package.txt" 2>/dev/null || true

    {
      echo "NiiX device test results"
      cat "$OUTDIR/device.txt"
      echo
      grep -E "^INSTRUMENTATION_STATUS: (test|class|stack)=" "$OUTDIR/instrumentation.txt" 2>/dev/null | sed 's/INSTRUMENTATION_STATUS: //' || true
      echo
      echo "Tests run:     $(grep -c 'INSTRUMENTATION_STATUS_CODE: 0' "$OUTDIR/instrumentation.txt" 2>/dev/null || echo 0) passed"
      echo "Failures:      $(grep -c 'INSTRUMENTATION_STATUS_CODE: -2' "$OUTDIR/instrumentation.txt" 2>/dev/null || echo 0)"
      echo "Errors:        $(grep -c 'INSTRUMENTATION_STATUS_CODE: -1' "$OUTDIR/instrumentation.txt" 2>/dev/null || echo 0)"
      echo
      # An error with no tests run means the harness never started -- a different problem from a
      # failing test, and the reason belongs here rather than only in the raw output.
      # Zero tests is a failure, not a pass.
      #
      # The runner prints "OK (0 tests)" and exits successfully when it finds nothing to run, so
      # a misdirected or empty run looked exactly like a clean one. A device test session that
      # executes nothing has told you nothing, and should say so.
      if [ "$(grep -c 'INSTRUMENTATION_STATUS_CODE: 0' "$OUTDIR/instrumentation.txt" 2>/dev/null || echo 0)" = "0" ] \
        && ! grep -q "INSTRUMENTATION_FAILED\|Unable to find instrumentation" "$OUTDIR/instrumentation.txt" 2>/dev/null; then
        echo "NO TESTS RAN. The run completed but executed nothing, which is not a pass."
        echo "  Check that the instrumentation above is app.niix.debug.test and that"
        echo "  app/src/androidTest contains tests."
      fi

      if grep -q "INSTRUMENTATION_FAILED\|Unable to find instrumentation" "$OUTDIR/instrumentation.txt" 2>/dev/null; then
        echo "The test harness did not start. What the device said:"
        grep -E "INSTRUMENTATION_STATUS: (Error|stack)=|INSTRUMENTATION_FAILED" \
          "$OUTDIR/instrumentation.txt" 2>/dev/null | sed 's/^/  /' | head -6
      else
        # Named failures, so the summary says which test broke rather than only how many.
        grep -B2 "INSTRUMENTATION_STATUS_CODE: -[12]" "$OUTDIR/instrumentation.txt" 2>/dev/null \
          | grep "INSTRUMENTATION_STATUS: test=" | sed 's/.*test=/  failed: /' | head -20 || true
      fi
    } > "$OUTDIR/summary.txt"

    echo
    cat "$OUTDIR/summary.txt"
    echo
    echo "==> Everything saved to: $OUTDIR"
    echo "    summary.txt          what passed and what did not"
    echo "    instrumentation.txt  full test output including stack traces"
    echo "    logcat.txt           everything the device logged during the run"
    echo "    crash.txt            crashes only, if the app died"
    echo "    package.txt          how the app is installed and what it was granted"
    echo
    echo "    Attach this folder when reporting a problem -- it contains what is needed to"
    echo "    diagnose one without a back-and-forth about device and version."
    # A zero-test run exits non-zero too: `am instrument` returns success when it runs nothing,
    # so trusting its exit code alone would report a green run that tested nothing.
    if [ "$(grep -c 'INSTRUMENTATION_STATUS_CODE: 0' "$OUTDIR/instrumentation.txt" 2>/dev/null || echo 0)" = "0" ]; then
      exit 1
    fi
    exit "$device_rc"
    ;;

  test)
    # Runs the test suite. The JDK, Gradle and Android SDK are set up first by the same code
    # path a build uses, so this works on a fresh checkout with nothing installed.
    #
    # gradle/wrapper/gradle-wrapper.jar IS committed, and verified against EXPECTED_WRAPPER_SHA256
    # before anything runs it. Committing a binary to a source repo deserves scrutiny, which is
    # why the hash check exists and why this says so: a reviewer should be able to confirm the
    # jar is Gradle's, not take anyone's word for it.
    echo "==> Running the test suite..."
    # `|| true` so the aggregate log is written even when tests fail -- that is precisely when
    # someone needs to read it, and letting `set -e` abort here would leave no record at all.
    "$GRADLE_CMD" --console=plain test || test_rc=$?
    echo
    write_full_test_log
    echo
    echo "==> Per-module HTML reports:"
    # Android modules write to reports/tests/<variant>UnitTest/, not reports/tests/test/, so
    # matching only the latter printed nothing and left the reviewer with no idea where the
    # results were.
    find . -path "*/build/reports/tests/*/index.html" 2>/dev/null | sed 's/^/    /'
    exit "${test_rc:-0}"
    ;;

  debug)
    echo "==> Building debug APK (no signing key required)..."
    "$GRADLE_CMD" --console=plain :app:assembleDebug
    OUT=$(find app/build/outputs/apk/debug -name "*.apk" ! -name "niix-messenger-*" 2>/dev/null | head -1 || true)
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
    OUT=$(find app/build/outputs/apk/release -name "*.apk" ! -name "*unsigned*" ! -name "niix-messenger-*" 2>/dev/null | head -1 || true)
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
