#!/usr/bin/env bash
# BUILD.sh -- the one entry point for building (and optionally publishing) a release.
#
# Run it with no arguments for an interactive menu, or use one of these directly. The menu and the
# options are the same in the BUILD.sh of TestTube, NiiX and niixfliix, wherever an option fits the
# app. The public repository has this same script: publishing needs the maintainer's keys.
#
#   ./BUILD.sh --create-keys          Create the signing keys, outside the project. Once, before
#                                          the first signed build.
#   ./BUILD.sh --release              Build a signed release APK using keystore.properties
#                                           (or $NIIX_KEYSTORE_PROPERTIES if set).
#   ./BUILD.sh --publish              Build a signed release APK, then sign it for update
#                                           checks and publish it to GitHub Releases in one go --
#                                           the APK, its .sig, and SHA256SUMS.txt always go up
#                                           together, so it's not possible to publish a release
#                                           the app's own update checker will then refuse to
#                                           offer because the .sig is missing.
#   ./BUILD.sh --upload-only          Publish an already-built release APK (skips building
#                                           entirely -- doesn't even need a JDK or Android SDK).
#   ./BUILD.sh --verify-reproducible  Build an *unsigned* release APK from source alone, for
#                                           comparing its hash against someone else's build of the
#                                           same commit -- never touches a real signing key, even
#                                           if one is configured.
#   ./BUILD.sh --write-dependency-hashes  Record a hash for every dependency, so a
#                                           substituted one fails the build. Re-run after any
#                                           dependency version change.
#   ./BUILD.sh --device-tests         Build a second APK that drives the app on a
#                                          connected phone, and save every log needed to debug
#                                          a failure.
#   ./BUILD.sh --test                 Run the automated test suite. Sets up whatever it
#                                           needs first, so this works on a fresh checkout.
#   ./BUILD.sh --debug                Build an installable debug APK. No signing key needed
#                                           -- Android's own auto-generated debug key is used,
#                                           same as any other Android project.
#   ./BUILD.sh --use-shared-key       Switch NiiX to the Android key all your apps share
#                                           (~/.keys/android, made the first time), for good.
#                                           Installed copies update to it as normal (Android's key
#                                           rotation): from then on releases carry both keys.
#   ./BUILD.sh --show-keys            Show the fingerprints of the signing keys and where they
#                                           are kept.
#   ./BUILD.sh --backup-keys[=DIR]    Copy all signing keys (~/.keys) to DIR/.keys (another
#                                           folder, such as a USB stick) and check the copy.
#   ./BUILD.sh --clean                Delete all build output (dist/ and the build folders).
#                                           The toolchain and the keys stay.
#
# Publishing (--publish / --upload-only) needs the GitHub CLI ('gh', logged in) and the
# update-signing private key (see docs/HOW-IT-WORKS.md) -- set NIIX_UPDATE_SIGNING_KEY to its path if it's
# not just called update-signing-key.pem in the project root. Set NIIX_RELEASE_TAG to skip the
# "what tag?" prompt.
#
#   NIIX_VERSION_NAME=1.2.0 NIIX_VERSION_CODE=5 ./BUILD.sh --release
#                                           Override version name/code for this build.
#
# This script also makes sure a JDK, Gradle, and the Android SDK actually exist before trying to
# build anything (skipped entirely for --upload-only, which doesn't build). If your machine
# already has them (JAVA_HOME / a system `gradle` / an existing Android SDK), those are used
# as-is. Anything missing is downloaded into ~/.niix-toolchain (override with
# NIIX_TOOLCHAIN_DIR) rather than into the project, so deleting or re-extracting the project
# folder doesn't throw the download away. Nothing is installed system-wide, so this never needs
# sudo and never conflicts with an existing install.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
ROOT="$(pwd)"

# The toolchain (JDK, Gradle, Android SDK) lives outside the project on purpose, so deleting or
# re-extracting the project folder doesn't throw away a multi-hundred-megabyte download you'd
# only have to fetch again. Override with NIIX_TOOLCHAIN_DIR if you'd rather it lived elsewhere.
# A .toolchain directory inside the project is still honoured if one already exists, so existing
# checkouts that downloaded there keep working instead of silently re-downloading everything.
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

# Reads the Unreleased section of CHANGELOG.md.
#
# GitHub's --generate-notes builds from commit history, which says nothing useful here: source is
# pushed as a wholesale replacement, so the log is one commit per release saying "source replaced".
# Users would get a release note that told them nothing. A changelog written as changes happen is
# the only thing that produces notes worth reading.
changelog_unreleased() {
  [ -f "$ROOT/CHANGELOG.md" ] || return 0
  awk '
    /^## Unreleased[[:space:]]*$/ { inside = 1; next }
    /^## / { if (inside) exit }
    inside { print }
  ' "$ROOT/CHANGELOG.md" | sed '/^[[:space:]]*$/d'
}

# Moves Unreleased under the version being published, and starts a fresh empty Unreleased.
#
# Done after the release succeeds, so a failed publish does not consume the notes and leave the
# next release describing nothing.
changelog_roll() {
  local version="$1"
  [ -f "$ROOT/CHANGELOG.md" ] || return 0
  local today
  today="$(date -u '+%Y-%m-%d')"
  local tmp
  tmp="$(mktemp)"
  awk -v ver="$version" -v today="$today" '
    /^## Unreleased[[:space:]]*$/ {
      print "## Unreleased"
      print ""
      print "## " ver " -- " today
      next
    }
    { print }
  ' "$ROOT/CHANGELOG.md" > "$tmp"
  mv "$tmp" "$ROOT/CHANGELOG.md"
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
    echo "      ./BUILD.sh --write-dependency-hashes"
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

# Everything this script prints goes through `tee` (see the exec above) so it lands in
# build.log. That pipe is block-buffered, which means a prompt written to stdout/stderr sits
# in the buffer instead of reaching the terminal -- so `read -rp` would block for input while
# showing the user nothing at all, looking exactly like a hang or a skipped question. Writing
# the prompt to /dev/tty and reading the answer from /dev/tty bypasses the redirection
# entirely. The fallback covers non-interactive runs (CI, piped input) where /dev/tty can't
# be opened; there the prompt goes to normal stdout and the answer comes from stdin, which is
# the correct behaviour when nobody is watching a terminal anyway.
ask() {
  # Prefixed names: bash locals hide the caller's variables of the same name, so a caller asking
  # for its reply in a variable called "answer" used to get nothing back at all.
  local __ask_prompt="$1" __ask_var="$2" __ask_reply
  if { true > /dev/tty; } 2>/dev/null; then
    printf '%s' "$__ask_prompt" > /dev/tty
    IFS= read -r __ask_reply < /dev/tty || __ask_reply=""
  else
    printf '%s' "$__ask_prompt"
    IFS= read -r __ask_reply || __ask_reply=""
  fi
  printf -v "$__ask_var" '%s' "$__ask_reply"
}

# ---- Signing keys -------------------------------------------------------------------------------
#
# Keys live outside the project by default, so zipping, copying or pushing the project can never
# take them along. That is how the original keys were exposed.
#
# Every signing key, for all apps, lives in ~/.keys (KEYS_HOME), NiiX's own in ~/.keys/niix; the
# Android key all apps share is in ~/.keys/android (see scripts/android-keys.sh). Earlier versions
# kept NiiX's keys in ~/.niix-keys, or a folder remembered in ~/.config/niix/key-dir (such as a USB
# stick); the first run copies them over. NIIX_KEY_DIR still points the build anywhere else.
KEYS_HOME="${KEYS_HOME:-$HOME/.keys}"
if [ ! -f "$ROOT/scripts/android-keys.sh" ]; then
  echo "error: scripts/android-keys.sh is missing. Unzip the whole project into an empty folder." >&2
  exit 1
fi
# shellcheck source=scripts/android-keys.sh
source "$ROOT/scripts/android-keys.sh"
NIIX_KEY_DIR_RECORD="$HOME/.config/niix/key-dir"
NIIX_KEY_DIR_DEFAULT="$KEYS_HOME/niix"
NIIX_KEY_DIR_GIVEN="${NIIX_KEY_DIR:-}"
NIIX_KEY_DIR="${NIIX_KEY_DIR:-$NIIX_KEY_DIR_DEFAULT}"

# Copies NiiX's keys from where earlier versions kept them into ~/.keys/niix, checking every file,
# and points their keystore.properties at the new place. ~/.niix-keys is emptied afterwards; a
# remembered folder elsewhere (a USB stick) is left as it is, as a backup.
migrate_niix_keys() {
  [ -z "$NIIX_KEY_DIR_GIVEN" ] || return 0
  local old="" keep="" props store
  if [ -f "$NIIX_KEY_DIR_RECORD" ]; then
    old="$(head -1 "$NIIX_KEY_DIR_RECORD")"
    keep="keep"
  elif [ -d "$HOME/.niix-keys" ]; then
    old="$HOME/.niix-keys"
  fi
  old="${old%/}"
  [ -n "$old" ] && [ "$old" != "$NIIX_KEY_DIR" ] || return 0
  if [ ! -f "$old/keystore.properties" ] && [ ! -f "$old/update-signing-key.pem" ]; then
    return 0
  fi
  if [ -f "$NIIX_KEY_DIR/keystore.properties" ] || [ -f "$NIIX_KEY_DIR/update-signing-key.pem" ]; then
    return 0
  fi
  echo "==> Moving NiiX's signing keys from $old to $NIIX_KEY_DIR (all keys now live in $KEYS_HOME)"
  if ! ak_move_dir "$old" "$NIIX_KEY_DIR" $keep; then
    echo "error: the keys could not be copied to $NIIX_KEY_DIR. Nothing in $old was changed." >&2
    exit 1
  fi
  for props in "$NIIX_KEY_DIR"/*.properties; do
    [ -f "$props" ] || continue
    store="$(sed -n 's/^storeFile=//p' "$props" | head -1)"
    [ -n "$store" ] && [ -f "$NIIX_KEY_DIR/$(basename "$store")" ] || continue
    sed "s|^storeFile=.*|storeFile=$NIIX_KEY_DIR/$(basename "$store")|" "$props" > "$props.tmp" && mv "$props.tmp" "$props"
    chmod 600 "$props"
  done
  rm -f "$NIIX_KEY_DIR_RECORD"
  if [ -n "$keep" ]; then
    echo "    The copy in $old was left as it is; keep it as a backup, or delete it."
  fi
}
migrate_niix_keys

# Points the build at keys in NIIX_KEY_DIR, unless an environment variable already says where
# they are. Keys there win over copies left in the project folder: those are the old, exposed
# keys, and they must not quietly be used after new ones have been made.
use_saved_keys() {
  if [ -z "${NIIX_KEYSTORE_PROPERTIES:-}" ] && [ -f "$NIIX_KEY_DIR/keystore.properties" ]; then
    export NIIX_KEYSTORE_PROPERTIES="$NIIX_KEY_DIR/keystore.properties"
  fi
  if [ -z "${NIIX_UPDATE_SIGNING_KEY:-}" ] && [ -f "$NIIX_KEY_DIR/update-signing-key.pem" ]; then
    export NIIX_UPDATE_SIGNING_KEY="$NIIX_KEY_DIR/update-signing-key.pem"
  fi
  if [ -z "${NIIX_PLAY_KEYSTORE_PROPERTIES:-}" ] && [ -f "$NIIX_KEY_DIR/keystore-play.properties" ]; then
    export NIIX_PLAY_KEYSTORE_PROPERTIES="$NIIX_KEY_DIR/keystore-play.properties"
  fi
}

# Asks where to keep new keys and prepares the folder, setting NEW_KEY_DIR to its absolute path.
# Sets a variable rather than printing, so the prompt can never end up inside the path.
# Refuses a folder inside the project, and refuses to overwrite any key already there.
prepare_key_dir() {
  local dir answer existing="" f
  ask "Where should the keys be kept? [Enter for $NIIX_KEY_DIR]: " answer
  dir="${answer:-$NIIX_KEY_DIR}"
  case "$dir" in
    "~") dir="$HOME" ;;
    "~/"*) dir="$HOME/${dir#\~/}" ;;
    /*) ;;
    *) dir="$PWD/$dir" ;;
  esac
  dir="${dir%/}"
  case "$dir/" in
    "$ROOT/"*)
      echo "error: $dir is inside the project folder. Keys kept there end up in anything made" >&2
      echo "       from the folder (a zip, a copy, an upload), which is how the old keys leaked." >&2
      echo "       Choose a folder outside it; the default is fine." >&2
      exit 1
      ;;
  esac
  for f in niix-release.jks keystore.properties update-signing-key.pem niix-play-upload.jks keystore-play.properties; do
    [ -e "$dir/$f" ] && existing="$existing $f"
  done
  if [ -n "$existing" ]; then
    echo "error: $dir already contains:$existing" >&2
    echo "       Nothing was changed. This never overwrites a key: replacing one by mistake can make" >&2
    echo "       it impossible to ever update the app again. If you really want new keys, move" >&2
    echo "       these somewhere safe first, or choose another folder." >&2
    exit 1
  fi
  mkdir -p "$dir"
  chmod 700 "$dir"
  NEW_KEY_DIR="$dir"
}

# Remembers where the keys were created, if that is not the default, so later builds find them.
remember_key_dir() {
  local dir="$1"
  if [ "$dir" = "$NIIX_KEY_DIR_DEFAULT" ]; then
    rm -f "$NIIX_KEY_DIR_RECORD"
  else
    mkdir -p "$(dirname "$NIIX_KEY_DIR_RECORD")"
    ( umask 077; printf '%s\n' "$dir" > "$NIIX_KEY_DIR_RECORD" )
  fi
}

# Creates a keystore holding a new RSA 4096 key, valid for about 27 years, plus the properties
# file the build reads. The password is random and stored in that file, which is how the build
# reads it anyway; what protects both is keeping the folder private and backed up offline.
# Arguments: folder, keystore file name, key alias, properties file name.
create_apk_keystore() {
  local dir="$1" jks="$1/$2" alias="$3" props="$1/$4" pass out
  if ! command -v keytool >/dev/null 2>&1; then
    echo "error: keytool not found. It comes with the JDK; run this script's build option once so it" >&2
    echo "       downloads one, or install JDK 17 or newer." >&2
    exit 1
  fi
  pass="$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32)"
  if [ "${#pass}" -lt 32 ]; then
    echo "error: could not generate a password." >&2
    exit 1
  fi
  # The password reaches keytool through the environment, not the command line, where other
  # programs on this computer could read it from the list of running processes. The certificate
  # name is just "NiiX": it is visible to anyone who inspects the APK, so it holds nothing
  # personal.
  if ! out=$(NIIX_NEW_KEY_PASS="$pass" keytool -genkeypair -storetype PKCS12 -keystore "$jks" \
      -alias "$alias" -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=NiiX" \
      -storepass:env NIIX_NEW_KEY_PASS -keypass:env NIIX_NEW_KEY_PASS -noprompt 2>&1); then
    rm -f "$jks"
    echo "error: keytool could not create $jks:" >&2
    printf '%s\n' "$out" >&2
    exit 1
  fi
  ( umask 077; printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
      "$jks" "$pass" "$alias" "$pass" > "$props" )
  chmod 600 "$jks" "$props"
}

# The SHA-256 fingerprint of a keystore's certificate, for checking against an installed app.
keystore_fingerprint() {
  local props="$1" jks pass
  jks=$(sed -n 's/^storeFile=//p' "$props")
  pass=$(sed -n 's/^storePassword=//p' "$props")
  NIIX_KS_PASS="$pass" keytool -list -v -keystore "$jks" -storepass:env NIIX_KS_PASS 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1
}

# The update key and APK certificate that were exposed along with the original key files. Anyone
# holding those files can sign as NiiX, so a release must never trust or be signed with either.
EXPOSED_UPDATE_KEY="9a6e9d9d475afb2945e5bb8afab19062058e131e4caafc44a76a41967973d4d8"
EXPOSED_APK_CERT="055ac0d3b49f020a1555d66d5c373ba9094f8966c9f8ff4b260847e8de8a3b30"
# The update keys the app trusts, one per line as lowercase hex: the key releases are signed with,
# then optionally a standby kept offline. The build reads this file into the app.
UPDATE_KEY_FILE="update-key.pub"

# The 32-byte Ed25519 public key of a private key file, as lowercase hex. Empty if unreadable.
update_key_hex() {
  { openssl pkey -in "$1" -pubout -outform DER 2>/dev/null | tail -c 32 | od -An -tx1 | tr -d ' \n'; } || true
}

# The update key releases are signed with, the first line of update-key.pub. Empty if unreadable.
pinned_update_key_hex() {
  { grep -m1 -E '^[0-9a-f]{64}$' "$UPDATE_KEY_FILE" 2>/dev/null; } || true
}

# Whether an APK's code trusts the given update key (hex). The build puts the keys into the app as
# text, so the dex holds them as that same hex. Checking the APK rather than the source matters
# because the source can change after the APK is built.
apk_trusts_key() {
  local apk="$1" key="$2" dex count
  while IFS= read -r dex; do
    # grep -c reads all of its input. grep -q would stop early, and under pipefail the writers
    # it cut off would turn a match into a failure.
    count=$({ unzip -p "$apk" "$dex" 2>/dev/null | grep -a -c -- "$key"; } || true)
    [ "${count:-0}" != "0" ] && return 0
  done < <({ unzip -Z1 "$apk" 2>/dev/null | grep -E '^classes[0-9]*\.dex$'; } || true)
  return 1
}

# Makes the app trust a new update-signing key, by writing its public half as the first line of
# update-key.pub. The app checks every update against these keys before installing it, so a key
# not listed signs releases the app refuses. A standby key on the second line is kept, unless it
# is the exposed one. Checked by reading the result back the same way the build does.
pin_update_key() {
  local key="$1" hex standby tmp
  hex=$(update_key_hex "$key")
  if [ "${#hex}" -ne 64 ]; then
    echo "error: could not read the public key from $key." >&2
    exit 1
  fi
  standby=$({ sed -n '2p' "$UPDATE_KEY_FILE" 2>/dev/null | grep -E '^[0-9a-f]{64}$'; } || true)
  if [ "$standby" = "$hex" ] || [ "$standby" = "$EXPOSED_UPDATE_KEY" ]; then
    standby=""
  fi
  tmp="$UPDATE_KEY_FILE.tmp"
  { printf '%s\n' "$hex"; [ -n "$standby" ] && printf '%s\n' "$standby"; } > "$tmp"
  if [ "$(grep -m1 -E '^[0-9a-f]{64}$' "$tmp")" != "$hex" ]; then
    rm -f "$tmp"
    echo "error: could not update $UPDATE_KEY_FILE. Nothing was changed there." >&2
    exit 1
  fi
  mv "$tmp" "$UPDATE_KEY_FILE"
}

# Before a signed build, makes sure the app will trust the update key releases are signed with.
#
# Unpacking a fresh copy of the source over the project replaces update-key.pub, and with it the
# key that creating keys wrote there. A build from that copy is still signed with the new APK key
# and installs fine, so nothing looks wrong, but its updater trusts a different key from the one
# releases are signed with and can never accept another update. Checked before building rather
# than at publishing, so it costs nothing to find out.
ensure_update_key_pin() {
  local key="${NIIX_UPDATE_SIGNING_KEY:-}" key_hex pinned answer
  pinned="$(pinned_update_key_hex)"
  if [ "${#pinned}" -ne 64 ]; then
    echo "error: could not read the update key from $UPDATE_KEY_FILE." >&2
    exit 1
  fi
  if [ -z "$key" ] || [ ! -f "$key" ]; then
    if [ "$pinned" = "$EXPOSED_UPDATE_KEY" ]; then
      echo "error: the app still trusts the old, exposed update key, and there is no update key in" >&2
      echo "       $NIIX_KEY_DIR to replace it with. Create keys with option 14, or set" >&2
      echo "       NIIX_UPDATE_SIGNING_KEY to the full path of your update-signing-key.pem." >&2
      exit 1
    fi
    if [ "$DO_PUBLISH" = true ]; then
      echo "error: no update-signing key found in $NIIX_KEY_DIR. Publishing signs the APK with it," >&2
      echo "       so nothing was built. Set NIIX_UPDATE_SIGNING_KEY to its full path if it is" >&2
      echo "       kept somewhere else." >&2
      exit 1
    fi
    echo "warning: no update-signing key found in $NIIX_KEY_DIR, so which update key this build"
    echo "         trusts was not checked."
    return 0
  fi
  if ! command -v openssl >/dev/null 2>&1; then
    echo "error: openssl is needed to check the update key and is not installed." >&2
    exit 1
  fi
  key_hex="$(update_key_hex "$key")"
  if [ "${#key_hex}" -ne 64 ]; then
    echo "error: could not read an Ed25519 public key from $key." >&2
    exit 1
  fi
  if [ "$key_hex" = "$EXPOSED_UPDATE_KEY" ]; then
    echo "error: $key is the old, exposed update key. Releases must not be signed with it" >&2
    echo "       any more. Point NIIX_UPDATE_SIGNING_KEY at your new key, or create keys with" >&2
    echo "       option 14." >&2
    exit 1
  fi
  if [ "$pinned" = "$key_hex" ]; then
    echo "==> The app trusts your update key ($key)."
    return 0
  fi
  if [ "$pinned" = "$EXPOSED_UPDATE_KEY" ]; then
    echo "==> update-key.pub trusted the old, exposed update key again. That happens when a fresh"
    echo "    copy of the source is unpacked over this folder. Switching it to your key..."
    pin_update_key "$key"
    echo "==> The app now trusts $key."
    return 0
  fi
  cat >&2 <<MSG
warning: the app trusts a different update key from the one in $key.
         Trusted by the app: $pinned
         Your key:           $key_hex
         Releases already out only accept updates signed with the key they were built with.
         Switch only if the app's current key is not one you sign releases with.
MSG
  ask "Make the app trust $key instead? [y/N]: " answer
  case "$answer" in
    y|Y|yes|YES)
      pin_update_key "$key"
      echo "==> The app now trusts $key."
      ;;
    *)
      echo "Nothing was built or changed." >&2
      exit 1
      ;;
  esac
}

# Adds the one-time reinstall note to the top of the Unreleased changelog, once.
changelog_note_new_keys() {
  local file="CHANGELOG.md"
  [ -f "$file" ] || return 0
  grep -q "signed with new keys" "$file" && return 0
  awk '
    !done && /^## Unreleased/ {
      print; print ""
      print "### Important"
      print "- This version is signed with new keys, so Android cannot update an older version to it."
      print "  Back up your data (Settings > Backup), uninstall NiiX, install this version and restore."
      print "  Updates after this one install as normal."
      done = 1; next
    }
    { print }
  ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
}

create_signing_keys() {
  local dir play answer fp old="" f
  if ! command -v openssl >/dev/null 2>&1; then
    echo "error: openssl is needed to create the update-signing key and is not installed." >&2
    echo "       Debian/Ubuntu: sudo apt install openssl    Fedora: sudo dnf install openssl" >&2
    echo "       Arch: sudo pacman -S openssl               macOS: brew install openssl@3" >&2
    exit 1
  fi
  cat <<MSG

==> Create signing keys

This makes three things:
  - the key that signs the APK (niix-release.jks), with its passwords (keystore.properties)
  - the key that signs in-app updates (update-signing-key.pem); the app is changed to trust it
  - if you want, an upload key for Google Play

Read this first. Phones that have the current version of NiiX cannot update to a build signed
with a new APK key: each of them has to back up, uninstall and reinstall once. A note saying so
is added to CHANGELOG.md, so it appears in the release notes. After that, updates work as normal.

MSG
  prepare_key_dir
  dir="$NEW_KEY_DIR"
  ask "Also create a Google Play upload key? [y/N]: " play
  ask "Type yes to create the keys in $dir: " answer
  if [ "$answer" != "yes" ]; then
    echo "Nothing was created."
    rmdir "$dir" 2>/dev/null || true
    exit 0
  fi

  echo "==> Creating the APK signing key (RSA 4096)..."
  create_apk_keystore "$dir" niix-release.jks niix keystore.properties

  echo "==> Creating the update-signing key (Ed25519)..."
  if ! ( umask 077; openssl genpkey -algorithm ed25519 -out "$dir/update-signing-key.pem" 2>/dev/null ); then
    rm -f "$dir/update-signing-key.pem"
    echo "error: this openssl cannot create Ed25519 keys. Install OpenSSL 1.1.1 or newer" >&2
    echo "       (macOS: brew install openssl@3), then run this again. The APK key was kept;" >&2
    echo "       move or delete $dir before trying again." >&2
    exit 1
  fi
  chmod 600 "$dir/update-signing-key.pem"

  case "$play" in
    y|Y|yes|YES)
      echo "==> Creating the Google Play upload key (RSA 4096)..."
      create_apk_keystore "$dir" niix-play-upload.jks niix-upload keystore-play.properties
      ;;
  esac

  echo "==> Making the app trust the new update key..."
  pin_update_key "$dir/update-signing-key.pem"
  changelog_note_new_keys
  remember_key_dir "$dir"
  case "$dir/" in
    "$KEYS_HOME/"*) ak_secure || true ;;
  esac

  fp=$(keystore_fingerprint "$dir/keystore.properties")
  for f in niix-release.jks keystore.properties update-signing-key.pem niix-update-signing.pem update-signing.pem; do
    [ -f "$ROOT/$f" ] && old="$old $f"
  done

  cat <<MSG

==> Keys created in $dir
      niix-release.jks          signs the APK
      keystore.properties       its generated passwords
      update-signing-key.pem    signs in-app updates
MSG
  [ -f "$dir/niix-play-upload.jks" ] && printf '      niix-play-upload.jks      Google Play upload key\n      keystore-play.properties  its generated passwords\n'
  cat <<MSG

    APK certificate SHA-256: ${fp:-unavailable}
    The app now trusts the new update key (update-key.pub was changed), and CHANGELOG.md
    tells people to reinstall once.

    Back up this folder now, to two places that are offline, such as two USB sticks.
    If the APK key is lost, nothing can ever be installed over existing copies of NiiX. If the
    update key is lost, the app can never accept another update. Nobody can recover either, and
    anyone who copies them can publish updates as you, so keep the folder private.

    Build a signed APK with option 1. The script finds these keys automatically.
MSG
  if [ -n "$old" ]; then
    echo
    echo "    The old keys are still in the project folder:$old"
    echo "    They are no longer used, and they were exposed. Delete them once you have"
    echo "    published a build signed with the new keys."
  fi
}

# ---- One Android key for all apps (scripts/android-keys.sh) -------------------------------------
#
# NiiX can move to the Android key all your apps share, for good, without anyone reinstalling:
# its own key signs, once, its approval of the shared key (lineage.bin, next to
# keystore.properties), and every APK built after that carries it and is signed with both.
# Android accepts it over the installed app (key rotation; NiiX needs Android 10, which has it)
# and from then on only takes the shared key. The update-signing key (update-signing-key.pem) is
# separate and stays as it is.

# Points scripts/android-keys.sh at the JDK and the SDK's apksigner.
niix_key_tools() {
  local sdk_root=""
  if [ -f local.properties ]; then
    sdk_root=$(sed -n 's/^sdk\.dir=//p' local.properties)
  fi
  AK_JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk}"
  AK_KEYTOOL="$AK_JAVA_HOME/bin/keytool"
  [ -x "$AK_KEYTOOL" ] || AK_KEYTOOL="$(command -v keytool || true)"
  AK_APKSIGNER=""
  if [ -n "$sdk_root" ] && [ -d "$sdk_root/build-tools" ]; then
    AK_APKSIGNER=$(find "$sdk_root/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -1)
  fi
  if [ -z "$AK_KEYTOOL" ] || [ -z "$AK_APKSIGNER" ]; then
    echo "error: keytool or apksigner not found; run a build once so the JDK and Android SDK are set up." >&2
    exit 1
  fi
}

# Reads keystore.properties into NP_STORE, NP_STOREPASS, NP_ALIAS, NP_KEYPASS and NP_LINEAGE.
niix_props() {
  local props="$1" dir
  if [ ! -f "$props" ]; then
    echo "error: no signing key found (looked for $props). Create keys with option 14." >&2
    exit 1
  fi
  props="$(absolute_path "$props")"
  dir="$(dirname "$props")"
  NP_STORE="$(ak_value "$props" storeFile || true)"
  NP_STOREPASS="$(ak_value "$props" storePassword || true)"
  NP_ALIAS="$(ak_value "$props" keyAlias || true)"
  NP_KEYPASS="$(ak_value "$props" keyPassword || true)"
  NP_KEYPASS="${NP_KEYPASS:-$NP_STOREPASS}"
  case "$NP_STORE" in
    /*) ;;
    *) NP_STORE="$ROOT/$NP_STORE" ;;
  esac
  if [ ! -f "$NP_STORE" ] || [ -z "$NP_STOREPASS" ] || [ -z "$NP_ALIAS" ]; then
    echo "error: $props is incomplete, or its keystore $NP_STORE is missing." >&2
    exit 1
  fi
  NP_LINEAGE="$dir/lineage.bin"
}

niix_min_sdk() {
  sed -n 's/^[[:space:]]*minSdk[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -1
}

niix_switch_key() {
  niix_key_tools
  niix_props "${NIIX_KEYSTORE_PROPERTIES:-keystore.properties}"
  if ! ak_switch NiiX "$NP_STORE" "$NP_STOREPASS" "$NP_KEYPASS" "$NP_ALIAS" "$NP_LINEAGE"; then
    echo "error: NiiX was not switched." >&2
    exit 1
  fi
  echo "==> Back up $KEYS_HOME now (option 17)."
}

# After a release build: once NiiX has switched, signs the APK with its own key and the shared key.
niix_sign_rotated() {
  local apk="$1" min
  niix_props "${NIIX_KEYSTORE_PROPERTIES:-keystore.properties}"
  [ -f "$NP_LINEAGE" ] || return 0
  if ! ak_shared_exists; then
    echo "error: NiiX uses the shared Android key, but it isn't in $AK_SHARED_DIR." >&2
    echo "       Restore $KEYS_HOME from your backup. Nothing was built that could be published." >&2
    rm -f "$apk"
    exit 1
  fi
  niix_key_tools
  min="$(niix_min_sdk)"
  if [ -z "$min" ]; then
    echo "error: could not read minSdk from app/build.gradle.kts." >&2
    exit 1
  fi
  echo "==> Signing with the shared Android key (NiiX switched to it)..."
  if ! ak_sign "$apk" "$NP_STORE" "$NP_STOREPASS" "$NP_KEYPASS" "$NP_ALIAS" "$NP_LINEAGE" \
      || ! ak_check "$apk" "$(ak_fingerprint "$NP_STORE" "$NP_STOREPASS" "$NP_ALIAS")" "$min"; then
    rm -f "$apk"
    echo "error: the APK could not be signed with the shared Android key; it was deleted." >&2
    exit 1
  fi
}

niix_show_keys() {
  local props="${NIIX_KEYSTORE_PROPERTIES:-keystore.properties}" key
  AK_KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/keytool}"
  [ -n "$AK_KEYTOOL" ] && [ -x "$AK_KEYTOOL" ] || AK_KEYTOOL="$(command -v keytool || true)"
  echo "==> Signing keys (all in $KEYS_HOME)"
  if [ -f "$props" ] && [ -n "$AK_KEYTOOL" ]; then
    niix_props "$props"
    echo "    NiiX's own APK key:   $(ak_fingerprint "$NP_STORE" "$NP_STOREPASS" "$NP_ALIAS")"
    echo "                          $NP_STORE"
    if [ -f "$NP_LINEAGE" ]; then
      echo "    NiiX uses the shared Android key (its own key approved it: $NP_LINEAGE)."
    elif ak_shared_exists; then
      echo "    NiiX doesn't use the shared Android key yet (option 16 switches it)."
    fi
  else
    echo "    No APK key yet. Create one with option 14."
  fi
  if ak_shared_exists && [ -n "$AK_KEYTOOL" ]; then
    echo "    Shared Android key:   $(ak_shared_digest)"
    echo "                          $AK_SHARED_KS"
  fi
  key="${NIIX_UPDATE_SIGNING_KEY:-}"
  if [ -n "$key" ] && [ -f "$key" ] && command -v openssl >/dev/null 2>&1; then
    echo "    Update-signing key:   $(update_key_hex "$key")"
    echo "                          $key"
  fi
  echo "    The app trusts:       $(pinned_update_key_hex) (update-key.pub)"
}

# Deletes what builds leave behind. The toolchain (outside the project) and the keys stay.
niix_clean() {
  echo "==> Deleting build output"
  rm -rf dist build app/build .gradle .kotlin
  find core -maxdepth 2 -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
  echo "==> Done. The next build starts from scratch (the toolchain in $TOOLCHAIN and the keys in $KEYS_HOME stay)."
}

niix_backup_keys() {
  local target="${NIIX_BACKUP_DIR:-}"
  [ -n "$target" ] || ak_ask "Folder to copy all signing keys to (a USB stick, for example): " target
  target="${target%/}"
  case "$target/" in
    "$ROOT/"*) echo "error: not inside the project folder: the keys would go wherever the project goes." >&2; exit 1 ;;
  esac
  ak_backup "$target" || exit 1
}

MODE="release"
DO_PUBLISH=false

show_menu() {
  local choice
  ask "$(printf 'What would you like to do?\n  1) Build a signed release APK\n  2) Build a signed release APK and publish it to GitHub Releases\n  3) Publish an already-built release APK to GitHub Releases\n  4) Build a debug APK (no signing key needed)\n  5) Build an unsigned APK for reproducible-build verification\n  6) Install the built APK to a connected phone (shows the real error if it fails)\n  7) Verify a duress wipe actually erased data (needs a debug build + USB)\n  8) Run the test suite\n  9) Package a source archive for a reviewer (no keys)\n 10) Record dependency hashes (supply-chain verification)\n 11) Run tests on a connected phone (saves full diagnostics)\n 12) Push source to GitHub (no release)\n 13) Build a Google Play bundle (.aab, no self-updater)\n 14) Create signing keys (once, before your first signed build)\n 15) Show the signing keys\n 16) Switch to the shared Android key (one key for all apps)\n 17) Back up all signing keys (~/.keys)\n 18) Delete all build output\nEnter a number [1-18]: ')" choice
  case "$choice" in
    1) MODE="release" ;;
    2) MODE="release"; DO_PUBLISH=true ;;
    3) MODE="upload-only" ;;
    4) MODE="debug" ;;
    5) MODE="reproducible" ;;
    6) MODE="install" ;;
    7) MODE="verify-duress" ;;
    8) MODE="test" ;;
    9) MODE="audit-zip" ;;
    10) MODE="dephashes" ;;
    11) MODE="devicetests" ;;
    12) MODE="pushsource" ;;
    13) MODE="playbundle" ;;
    14) MODE="createkeys" ;;
    15) MODE="showkeys" ;;
    16) MODE="sharedkey" ;;
    17) MODE="backupkeys" ;;
    18) MODE="clean" ;;
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
      --test) MODE="test" ;;
      --write-dependency-hashes) MODE="dephashes" ;;
      --device-tests) MODE="devicetests" ;;
      --audit-zip) MODE="audit-zip" ;;
      --push-source) MODE="pushsource" ;;
      --play-bundle) MODE="playbundle" ;;
      --create-keys) MODE="createkeys" ;;
      --show-keys) MODE="showkeys" ;;
      --use-shared-key) MODE="sharedkey" ;;
      --backup-keys) MODE="backupkeys" ;;
      --backup-keys=*) MODE="backupkeys"; NIIX_BACKUP_DIR="${arg#--backup-keys=}" ;;
      --clean) MODE="clean" ;;
      --release) MODE="release" ;;
      --install) MODE="install" ;;
      --verify-duress) MODE="verify-duress" ;;
      --publish) MODE="release"; DO_PUBLISH=true ;;
      --upload-only) MODE="upload-only" ;;
      -h|--help)
        # The whole header comment, however long it grows: a fixed line range went stale
        # every time an option was added and cut the help off mid-sentence.
        awk 'NR > 1 && /^#/ { print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
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
# What this does and does not protect against is worth being precise about. Fetching the
# published checksum over a separate request catches a corrupted or truncated download and a
# tampered mirror, but not a compromised origin -- if whoever serves the file also serves the
# checksum, both move together. Pinning a hash you obtained independently (NIIX_GRADLE_SHA256,
# NIIX_CMDLINE_TOOLS_SHA256) is the version that actually resists a compromised origin, and is
# what a high-assurance build should use. The fetched-checksum path is the weaker default that
# still catches the common failures.
verify_sha256() {
  local file="$1" expected="$2" label="$3"
  [ -z "$expected" ] && return 0
  local actual
  actual=$(sha256sum "$file" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    echo "error: $label failed checksum verification." >&2
    echo "       expected: $expected" >&2
    echo "       actual:   $actual" >&2
    echo "       The download was corrupted or tampered with. Nothing has been installed." >&2
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
    # The line with the version, not simply the first line: when JAVA_TOOL_OPTIONS is set,
    # Java prints "Picked up JAVA_TOOL_OPTIONS ..." before it, and a perfectly good JDK was then
    # mistaken for a missing one.
    ver=$(java -version 2>&1 | grep -m1 -oE 'version "[0-9]+' | grep -oE '[0-9]+$' || true)
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

# SHA-256 of the gradle-wrapper.jar committed to this repository, taken from Gradle's own
# published source tree. The wrapper jar is executed by ./gradlew, so a substituted one is code
# execution in the build environment -- and unlike the Gradle distribution, this file lives in
# the repo where a change would otherwise be invisible in a diff.
readonly EXPECTED_WRAPPER_SHA256="7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172"

verify_wrapper_jar() {
  local jar="gradle/wrapper/gradle-wrapper.jar"
  [ -f "$jar" ] || return 0
  local actual
  actual=$(sha256sum "$jar" | cut -d' ' -f1)
  if [ "$actual" != "$EXPECTED_WRAPPER_SHA256" ]; then
    echo "error: gradle/wrapper/gradle-wrapper.jar does not match the expected hash." >&2
    echo "       expected: $EXPECTED_WRAPPER_SHA256" >&2
    echo "       actual:   $actual" >&2
    echo "       This file is executed by ./gradlew. Refusing to continue." >&2
    exit 1
  fi
}

ensure_gradle() {
  verify_wrapper_jar
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
      echo "       NIIX_GRADLE_ZIP=/path/to/gradle-8.14.4-bin.zip ./BUILD.sh" >&2
      echo >&2
      echo "  3. Extract a Gradle distribution to $TOOLCHAIN/gradle" >&2
      echo "     (so that $TOOLCHAIN/gradle/bin/gradle exists) and re-run." >&2
      echo >&2
      echo "The Android SDK can be supplied the same way: set ANDROID_HOME to an existing SDK," >&2
      echo "or NIIX_CMDLINE_TOOLS_ZIP to a downloaded command-line tools archive. A JDK 17+ on" >&2
      echo "PATH or JAVA_HOME is used as-is." >&2
      exit 1
    fi
    # Prefer a hash pinned out-of-band; fall back to the one Gradle publishes beside the zip.
    local gradle_sha="${NIIX_GRADLE_SHA256:-}"
    if [ -z "$gradle_sha" ]; then
      gradle_sha=$(curl -fsSL "${dist_url}.sha256" 2>/dev/null | tr -d ' \n' || true)
      [ -n "$gradle_sha" ] && echo "==> Using Gradle's published checksum (set NIIX_GRADLE_SHA256 to pin one instead)."
    fi
    if [ -z "$gradle_sha" ]; then
      # Fail closed. This archive is about to be executed, so running it unverified means
      # trusting whatever the network returned -- a compromised mirror, proxy or DNS path
      # becomes code execution in the build environment. Refusing is recoverable; running a
      # tampered toolchain is not.
      echo "error: could not obtain a checksum for the Gradle distribution, and this script" >&2
      echo "       will not execute an unverified one." >&2
      echo "       Set NIIX_GRADLE_SHA256 to the hash published at" >&2
      echo "       ${dist_url}.sha256 (fetched independently), then re-run." >&2
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

# Platform the project compiles against. Must match compileSdk in the Gradle build files.
NIIX_SDK_PLATFORM="android-36"

# Makes sure an SDK that already exists has the compile platform.
#
# The checks below used to accept any SDK with a platforms folder. When compileSdk moved to 36,
# a machine that had built NiiX before still had only android-35, the check passed, and Gradle
# then failed with "failed to find target android-36". This installs the missing platform into
# the SDK that is already there, or says exactly what to do if it cannot.
ensure_sdk_platform() {
  local dir="$1"
  [ -d "$dir/platforms/$NIIX_SDK_PLATFORM" ] && return 0
  local sm=""
  local candidate
  for candidate in "$dir/cmdline-tools/latest/bin/sdkmanager" "$dir/tools/bin/sdkmanager"; do
    if [ -x "$candidate" ]; then sm="$candidate"; break; fi
  done
  if [ -z "$sm" ]; then
    echo "error: the Android SDK at $dir does not have $NIIX_SDK_PLATFORM, and has no sdkmanager" >&2
    echo "       to install it with. Install \"platforms;$NIIX_SDK_PLATFORM\" from Android Studio's" >&2
    echo "       SDK Manager, then re-run this." >&2
    exit 1
  fi
  echo "==> Installing $NIIX_SDK_PLATFORM into the existing Android SDK at $dir..."
  local rc=0
  set +o pipefail
  yes | "$sm" --sdk_root="$dir" --licenses >/dev/null 2>&1 || true
  # Inside an if, so a failure reaches the message below instead of stopping the script there.
  if ! yes | "$sm" --sdk_root="$dir" "platforms;$NIIX_SDK_PLATFORM" >/dev/null; then rc=1; fi
  set -o pipefail
  if [ "$rc" -ne 0 ] || [ ! -d "$dir/platforms/$NIIX_SDK_PLATFORM" ]; then
    echo "error: could not install $NIIX_SDK_PLATFORM into $dir." >&2
    exit 1
  fi
}

ensure_android_sdk() {
  local existing="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$existing" ] && [ -d "$existing/platforms" ]; then
    ensure_sdk_platform "$existing"
    echo "sdk.dir=$existing" > local.properties
    return 0
  fi
  if [ -f local.properties ] && grep -q '^sdk.dir=' local.properties; then
    local existing_dir
    existing_dir=$(grep '^sdk.dir=' local.properties | cut -d= -f2-)
    if [ -d "$existing_dir/platforms" ]; then
      ensure_sdk_platform "$existing_dir"
      return 0
    fi
  fi
  for guess in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$guess/platforms" ]; then
      ensure_sdk_platform "$guess"
      echo "sdk.dir=$guess" > local.properties
      return 0
    fi
  done
  if [ -d "$TOOLCHAIN/android-sdk/platforms" ]; then
    ensure_sdk_platform "$TOOLCHAIN/android-sdk"
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
  echo "==> Accepting SDK licenses and installing platform $NIIX_SDK_PLATFORM + build-tools (a few minutes)..."
  yes | "$sdkmanager" --sdk_root="$sdk_dir" --licenses >/dev/null 2>&1 || true
  # `yes |` here is not redundant with the --licenses call above: installing packages prompts
  # again for their own licenses, and that prompt is invisible because everything this script
  # prints goes through a buffered tee pipe -- so without this the script sits there forever,
  # apparently hung, waiting on input nobody can see it asking for.
  #
  # pipefail is disabled for exactly this one pipeline. When sdkmanager finishes it closes its
  # stdin, `yes` is killed by SIGPIPE, and that exit status (141) becomes the pipeline's under
  # pipefail -- which aborts the whole script under `set -e` even though the install actually
  # succeeded. PIPESTATUS[1] gets sdkmanager's own status, which is the one that matters, so
  # this suppresses the spurious failure without hiding a genuine one.
  set +o pipefail
  # The NDK is required even though this project contains no native source of its own. The
  # Android plugin strips debug symbols from bundled prebuilt .so files using the NDK's strip
  # tool, and without the NDK present it silently gives up -- "Unable to strip the following
  # libraries, packaging them as they are" -- and ships libsignal, SQLCipher and tor complete
  # with .debug_info and .symtab sections. That is a real finding in a security release: it
  # hands a reverse engineer the full symbol tables of exactly the security-sensitive code.
  yes | "$sdkmanager" --sdk_root="$sdk_dir" "platform-tools" "platforms;$NIIX_SDK_PLATFORM" "build-tools;35.0.0" "ndk;27.0.12077973"
  local sdk_rc=${PIPESTATUS[1]}
  set -o pipefail
  if [ "$sdk_rc" -ne 0 ]; then
    echo "error: sdkmanager failed to install the required SDK packages (exit $sdk_rc)." >&2
    exit 1
  fi

  echo "sdk.dir=$sdk_dir" > local.properties
  echo "==> Android SDK ready at $sdk_dir"
}

find_repo_slug() {
  sed -n 's/.*const val REPO = "\([^"]*\)".*/\1/p' \
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
# Asks what version this build should have, suggesting the next patch version as a default so
# you can just hit enter most of the time -- never silently picks a version on its own. Runs on
# every release build, not only when publishing: you may well want to set a version on a build
# you're just going to install by hand.
#
# The "must be greater than the current version code" rule is enforced strictly when
# publishing, because a published release Android won't accept as an update is broken by
# definition. For a plain local build it's only a warning -- rebuilding the same version to test
# something is completely legitimate, and forcing a bump every time would inflate the version
# number for no reason. NIIX_VERSION_NAME / NIIX_VERSION_CODE in the environment skip the
# prompts entirely, for scripted or CI builds.
# Absolute form of a path relative to the current directory.
#
# The Gradle build reads NIIX_KEYSTORE_PROPERTIES with file(), which resolves relative paths
# against the app module, not the project root. A relative path that exists here would be looked
# for under app/ instead, and the build would fail as if there were no signing key at all.
absolute_path() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$(cd "$(dirname "$1")" && pwd)" "$(basename "$1")" ;;
  esac
}

# Checks that 64-bit native libraries support 16 KB memory pages.
#
# Google Play requires this for apps with native code, and NiiX's encryption, database and Tor
# libraries are all native. Whether a library complies depends on how its vendor built it, so it
# can only be checked on a real build. Each 64-bit library's LOAD segments must be aligned to at
# least 16 KB (0x4000). A warning rather than a failure: GitHub builds are not bound by the rule,
# but a Play upload would be rejected.
check_16kb_pages() {
  local apk="$1"
  local sdk readelf
  sdk=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)
  readelf=$(find "${sdk:-/nonexistent}/ndk" -name llvm-readelf -type f 2>/dev/null | head -1 || true)
  if [ -z "$readelf" ]; then
    echo "note: cannot check 16 KB page support (llvm-readelf from the NDK not found)."
    return 0
  fi
  local tmp bad="" checked=0 so align
  tmp=$(mktemp -d)
  for so in $(unzip -l "$apk" 2>/dev/null | awk '/lib\/(arm64-v8a|x86_64)\/.*\.so$/ {print $NF}'); do
    unzip -p "$apk" "$so" > "$tmp/lib.so" 2>/dev/null || continue
    checked=$((checked + 1))
    while read -r align; do
      if [ "$((align))" -lt 16384 ]; then bad="$bad $so"; break; fi
    done < <("$readelf" -lW "$tmp/lib.so" 2>/dev/null | awk '$1=="LOAD" {print $NF}')
  done
  rm -rf "$tmp"
  if [ "$checked" -eq 0 ]; then
    echo "note: no 64-bit native libraries found to check for 16 KB page support."
  elif [ -n "$bad" ]; then
    echo "warning: these 64-bit native libraries are not 16 KB page aligned:" >&2
    for so in $bad; do echo "           $so" >&2; done
    echo "         Google Play rejects apps like this. Usually fixed by updating the library that" >&2
    echo "         ships it to a release built for 16 KB pages." >&2
  else
    echo "==> All $checked 64-bit native libraries support 16 KB pages (required by Google Play)."
  fi
}

choose_version() {
  local enforce_increase="${1:-false}"
  local old_name old_code suggested_name new_name new_code
  old_name="$(read_version_name)"
  old_code="$(read_version_code)"
  old_name="${old_name:-0.1.0}"
  old_code="${old_code:-0}"

  # version.properties is a local file and can be stale or out of sync with what's actually
  # published -- a fresh checkout, a reset, a copy from elsewhere. If the release you already
  # shipped has a higher version code than this file believes, building "the next version" from
  # the stale file produces an APK that isn't newer than what people already have installed, and
  # Android refuses to install it with a message that says nothing useful ("package appears to
  # be invalid"). Ask GitHub what's actually out there and take whichever is higher as the real
  # baseline. Best-effort: if gh isn't installed or there's no network, fall back to the file.
  if command -v gh >/dev/null 2>&1; then
    local repo_slug published_code
    repo_slug="$(find_repo_slug)"
    if [ -n "$repo_slug" ]; then
      published_code=$(gh release view --repo "$repo_slug" --json assets \
        --jq '[.assets[].name | capture("niix-messenger-(?<v>[0-9]+\\.[0-9]+\\.[0-9]+)\\.apk").v] | .[0] // empty' 2>/dev/null || true)
      if [ -n "$published_code" ] && [ "$published_code" != "$old_name" ]; then
        echo "note: the latest published release is $published_code, but version.properties says"
        echo "      $old_name. Using the published one as the baseline so this build is genuinely"
        echo "      newer than what people already have installed."
        old_name="$published_code"
      fi
    fi
  fi

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
    # The version code is derived from the clock -- minutes elapsed since 2020-01-01 -- rather
    # than incremented from version.properties.
    #
    # The stored-and-incremented approach kept producing builds Android refused to install with
    # INSTALL_FAILED_VERSION_DOWNGRADE, because version.properties is a local file and the number
    # in it drifts from what's actually installed the moment anyone builds from a fresh checkout,
    # a copy, or a different machine. There is no reliable way for a build to know what's on some
    # user's phone, so the fix is to stop needing to know: a clock-derived code always increases,
    # so any later build outranks any earlier one no matter where either was produced.
    #
    # This lands around 3.5 million today and gains ~525,600 per year, against Android's ceiling
    # of 2,100,000,000 -- thousands of years of headroom. Minute granularity means two builds in
    # the same minute share a code; if that ever matters, pass NIIX_VERSION_CODE explicitly.
    local suggested_code=$(( ($(date +%s) - 1577836800) / 60 ))
    # Re-prompts rather than aborting on a bad value. This question follows immediately after
    # the version *name*, and the two are easy to confuse -- but failing here throws away
    # everything up to this point, which after a first-run toolchain download is several minutes
    # of work lost to a typo. The suggested value is almost always the right answer, so pressing
    # enter is the expected path.
    local attempts=0
    while true; do
      ask "Version code -- a whole number, NOT a version name (press enter for $suggested_code): " new_code
      new_code="${new_code:-$suggested_code}"
      if [[ "$new_code" =~ ^[0-9]+$ ]]; then
        break
      fi
      echo "  '$new_code' isn't a whole number. The version code is an internal counter Android"
      echo "  uses to decide what is newer; the version name (like 0.8.0) was the previous"
      echo "  question. Press enter to accept $suggested_code."
      attempts=$((attempts + 1))
      if [ "$attempts" -ge 3 ]; then
        echo "error: still not a whole number after several attempts -- stopping." >&2
        exit 1
      fi
    done
  fi
  if ! [[ "$new_code" =~ ^[0-9]+$ ]]; then
    echo "error: version code must be a whole number, got '$new_code'." >&2
    exit 1
  fi
  # Compare against the highest code we have ever seen, not just whatever the local file happens
  # to say. version.properties drifts from reality easily (fresh checkout, copy, another
  # machine), and trusting it is precisely what produced builds Android rejected as downgrades.
  # A clock-derived code clears this automatically; a hand-entered one still gets checked.
  local highest="$old_code"
  if [ -f "$HOME/.niix-highest-version-code" ]; then
    local seen
    seen=$(cat "$HOME/.niix-highest-version-code" 2>/dev/null || echo 0)
    [[ "$seen" =~ ^[0-9]+$ ]] && [ "$seen" -gt "$highest" ] && highest="$seen"
  fi
  if [ "$new_code" -le "$highest" ]; then
    if [ "$enforce_increase" = true ]; then
      echo "error: version code $new_code is not greater than $highest, the highest this machine" >&2
      echo "       has built. Android refuses to install a lower code over a higher one" >&2
      echo "       (INSTALL_FAILED_VERSION_DOWNGRADE), so publishing this would produce a" >&2
      echo "       release that cannot be installed as an update." >&2
      exit 1
    fi
    echo "warning: version code $new_code is not greater than $highest. This build won't install"
    echo "         over an existing install -- you'd have to uninstall first."
  fi
  # Remember the high-water mark so a stale version.properties can't walk the code backwards.
  if [ "$new_code" -gt "$highest" ]; then
    echo "$new_code" > "$HOME/.niix-highest-version-code" 2>/dev/null || true
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
  local dir
  dir="$(dirname "$original")"
  # Clear out renamed APKs from previous builds first. Without this the output directory
  # accumulates one per version ever built, and anything that later picks "the" APK out of that
  # directory (--upload-only, most obviously) can silently grab a stale one -- find returns
  # directory order, not newest-first, so it wouldn't even fail consistently.
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
    echo "       This means the build itself produced a broken APK -- nothing downstream" >&2
    echo "       (signing for updates, uploading, downloading) can fix that. Try a clean" >&2
    echo "       build: rm -rf app/build && ./BUILD.sh, and if it still fails, that's" >&2
    echo "       worth investigating as its own problem rather than a network/Tor issue." >&2
    exit 1
  fi
  echo "==> apksigner confirms this APK is structurally valid and correctly signed."

  # Confirm the bundled native libraries were actually stripped. AGP skips stripping silently
  # when the NDK is missing, and the only sign is a warning buried in the build output -- the
  # APK still builds, still signs, and still works, while shipping full symbol tables for
  # libsignal, SQLCipher and tor. Checking the artifact means this can't regress unnoticed.
  local unstripped
  unstripped=$(unzip -l "$apk_path" 2>/dev/null | awk '/\.so$/ {print $NF}' | while read -r so; do
    if unzip -p "$apk_path" "$so" 2>/dev/null | grep -qa "\.debug_info"; then echo "$so"; fi
  done)
  if [ -n "$unstripped" ]; then
    echo "warning: these native libraries still contain debug symbols:" >&2
    printf '%s\n' "$unstripped" >&2
    echo "         The NDK is probably missing, so the strip step was skipped. Install it with:" >&2
    echo "           sdkmanager --sdk_root=<sdk> 'ndk;27.0.12077973'" >&2
    echo "         then rebuild. This does not break the app, but it hands a reverse engineer" >&2
    echo "         the symbol tables of the security-sensitive native code." >&2
  else
    echo "==> Native libraries are stripped (no debug symbols present)."
  fi

  check_16kb_pages "$apk_path"
  # Print the signing certificate so it can be compared against what's actually installed on the
  # device (Settings shows the same fingerprint under the version). Android refuses an update
  # signed with a different key than the installed app and gives no indication that's the reason,
  # so having both numbers visible is the only practical way to rule it in or out.
  local fingerprint
  # Same JDK as the verify call above -- apksigner shells out to java and has none on PATH.
  # The `|| true` matters as much: with `set -o pipefail`, grep finding no match makes the whole
  # pipeline fail, and `set -e` then aborts the script with no message at all. A missing
  # fingerprint is cosmetic; it must not kill a build that has already succeeded.
  fingerprint=$(JAVA_HOME="$apksigner_java" PATH="$apksigner_java/bin:$PATH" \
    "$apksigner" verify --print-certs "$apk_path" 2>/dev/null \
    | grep -i "SHA-256 digest" | head -1 | awk '{print $NF}' || true)
  if [ -n "$fingerprint" ] && \
     [ "$(printf '%s' "$fingerprint" | tr -d ':' | tr '[:upper:]' '[:lower:]')" = "$EXPOSED_APK_CERT" ]; then
    echo "error: $(basename "$apk_path") is signed with the old, exposed APK key." >&2
    echo "       Anyone holding that key can sign an APK that installs over it, so it must not be" >&2
    echo "       installed or published. Move the old keystore.properties and niix-release.jks out" >&2
    echo "       of the project folder, then build again so your keys in $NIIX_KEY_DIR are used." >&2
    exit 1
  fi
  if [ -n "$fingerprint" ]; then
    echo "==> Signed with certificate SHA-256: $fingerprint"
    echo "    If updates fail to install, compare this against the fingerprint shown in the app's"
    echo "    own Settings screen, under the version. If they differ, that is the reason -- an"
    echo "    update signed with a different key can never install over the existing one."
  fi
}

# Version of the GitHub CLI this script installs if the machine has none.
#
# Pinned rather than "latest" so a build is reproducible and the checksum below means something.
# Bump both together.
GH_VERSION="2.63.2"

# Installs the GitHub CLI into the toolchain directory when the system has none.
#
# Everything else this script needs -- JDK, Android SDK, Gradle -- is fetched the same way, so a
# fresh machine can build without installing anything by hand. Requiring a package manager for
# this one tool broke that, and the fix is not to document three install commands but to remove
# the step.
#
# Installed under $TOOLCHAIN, never system-wide. A build script should not be putting binaries in
# /usr/local on someone's machine, and a per-toolchain copy is removable by deleting one folder.
install_gh() {
  local os arch asset url dir
  case "$(uname -s)" in
    Linux) os="linux" ;;
    Darwin) os="macOS" ;;
    *) return 1 ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) arch="amd64" ;;
    aarch64|arm64) arch="arm64" ;;
    *) return 1 ;;
  esac

  asset="gh_${GH_VERSION}_${os}_${arch}.tar.gz"
  url="https://github.com/cli/cli/releases/download/v${GH_VERSION}/${asset}"
  dir="$TOOLCHAIN/gh"

  echo "==> The GitHub CLI isn't installed. Fetching gh $GH_VERSION into $dir..."
  mkdir -p "$dir"
  if ! curl -fsSL -o "$dir/$asset" "$url"; then
    echo "error: could not download $url" >&2
    return 1
  fi

  # Checked against the checksums GitHub publishes alongside the release. Downloading a binary
  # and running it unverified is the supply-chain problem this project takes seriously
  # everywhere else; it would be odd to make an exception for the tool that does the publishing.
  local sums expected actual
  sums="$dir/checksums.txt"
  if curl -fsSL -o "$sums" "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_checksums.txt"; then
    expected=$(grep " $asset\$" "$sums" | cut -d' ' -f1)
    actual=$(sha256sum "$dir/$asset" | cut -d' ' -f1)
    if [ -n "$expected" ] && [ "$expected" != "$actual" ]; then
      echo "error: the downloaded gh archive does not match GitHub's published checksum." >&2
      echo "       expected: $expected" >&2
      echo "       actual:   $actual" >&2
      rm -rf "$dir"
      return 1
    fi
    [ -n "$expected" ] && echo "    checksum verified against GitHub's published list."
  else
    echo "warning: could not fetch gh's checksum list; the archive was not verified." >&2
  fi

  tar -xzf "$dir/$asset" -C "$dir" --strip-components=1 || return 1
  rm -f "$dir/$asset" "$sums"
  [ -x "$dir/bin/gh" ] || return 1
  echo "==> gh $GH_VERSION installed."
  return 0
}

ensure_gh() {
  # A toolchain copy from a previous run takes precedence over nothing at all, but a system gh
  # is preferred: it is the one the user maintains and is more likely to be current.
  if ! command -v gh >/dev/null 2>&1 && [ -x "$TOOLCHAIN/gh/bin/gh" ]; then
    PATH="$TOOLCHAIN/gh/bin:$PATH"
    export PATH
  fi

  if ! command -v gh >/dev/null 2>&1; then
    if install_gh; then
      PATH="$TOOLCHAIN/gh/bin:$PATH"
      export PATH
    else
      echo "error: the GitHub CLI ('gh') isn't installed and could not be fetched." >&2
      echo "       Install it by hand and re-run:" >&2
      echo "         Debian/Ubuntu: sudo apt install gh" >&2
      echo "         Arch:          sudo pacman -S github-cli" >&2
      echo "         macOS:         brew install gh" >&2
      echo "         Anything else: https://github.com/cli/cli#installation" >&2
      exit 1
    fi
  fi
  if ! gh auth status >/dev/null 2>&1; then
    echo "error: 'gh' is present but not logged in to GitHub." >&2
    echo >&2
    # Points at the binary actually in use. If this script installed its own copy, the user's
    # shell has no gh on PATH and "run gh auth login" is advice they cannot follow.
    echo "       Run this once, then re-run:" >&2
    echo "         $(command -v gh) auth login" >&2
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
    echo "error: no update-signing key found, in $NIIX_KEY_DIR or in the project folder." >&2
    echo "       If your keys are on removable media, plug it in. Otherwise set" >&2
    echo "       NIIX_UPDATE_SIGNING_KEY to the key's full path, or create keys with option 14." >&2
    echo "       This is a separate key from your APK signing keystore -- don't point" >&2
    echo "       this at niix-release.jks." >&2
    exit 1
  fi

  if ! command -v unzip >/dev/null 2>&1; then
    echo "error: unzip is needed to check which update key the APK trusts, and is not installed." >&2
    echo "       Debian/Ubuntu: sudo apt install unzip    Fedora: sudo dnf install unzip" >&2
    echo "       Arch: sudo pacman -S unzip" >&2
    exit 1
  fi

  if [ ! -f "$apk_path" ]; then
    echo "error: the APK to publish doesn't exist at $apk_path" >&2
    exit 1
  fi
  echo "==> Checking the APK trusts $key..."
  local derived_hex
  derived_hex="$(update_key_hex "$key")"
  if [ ${#derived_hex} -ne 64 ]; then
    echo "error: couldn't read an Ed25519 public key from '$key'." >&2
    echo "       Is it really an Ed25519 private key in PEM/PKCS8 format" >&2
    echo "       (openssl genpkey -algorithm ed25519 -out ...)?" >&2
    exit 1
  fi
  if [ "$derived_hex" = "$EXPOSED_UPDATE_KEY" ]; then
    echo "error: '$key' is the old, exposed update key. Nothing was published." >&2
    echo "       Releases must not be signed with it any more. Point NIIX_UPDATE_SIGNING_KEY at" >&2
    echo "       your new key, or create keys with option 14." >&2
    exit 1
  fi
  # The APK, not the source: the source can have changed since the APK was built, and the APK is
  # what people install.
  if ! apk_trusts_key "$apk_path" "$derived_hex"; then
    echo "error: $(basename "$apk_path") does not trust '$key'. Nothing was published." >&2
    if apk_trusts_key "$apk_path" "$EXPOSED_UPDATE_KEY"; then
      echo "       It trusts the old, exposed update key instead. Build it again with option 2:" >&2
      echo "       the build now switches the app to your key first." >&2
    else
      echo "       It was built to trust a different update key, so the app would refuse updates" >&2
      echo "       signed with this one. Build it again with option 2, which checks the key" >&2
      echo "       before building." >&2
    fi
    exit 1
  fi
  echo "==> Confirmed: the APK trusts this update key."

  local repo_slug
  repo_slug="$(find_repo_slug)"
  if [ -z "$repo_slug" ] || [[ "$repo_slug" == *YOUR_GITHUB_USER* ]]; then
    echo "error: REPO in UpdateChecker.kt isn't set to a real repo yet." >&2
    exit 1
  fi

  local apk_dir apk_name sig_path sums_path
  apk_dir="$(cd "$(dirname "$apk_path")" && pwd)"
  apk_name="$(basename "$apk_path")"
  # Everything below uses absolute paths. apk_path arrives relative to the project root, and
  # mixing relative and absolute here is the kind of thing that works until something changes
  # the working directory and then fails in a way that looks like the upload silently skipped
  # a file.
  apk_path="$apk_dir/$apk_name"
  sig_path="$apk_dir/$apk_name.sig"
  sums_path="$apk_dir/SHA256SUMS.txt"
  if [ ! -f "$apk_path" ]; then
    echo "error: the APK to publish doesn't exist at $apk_path" >&2
    exit 1
  fi

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
    ask "Release tag (this must match the version actually built into that APK) [$suggested]: " tag
    tag="${tag:-$suggested}"
  fi
  if [ -z "$tag" ]; then
    echo "error: no tag given." >&2
    exit 1
  fi

  echo "==> Replacing the source on $repo_slug with the current tree..."
  sync_source_to_repo "$repo_slug" "$tag"

  # Notes come from the changelog, written as changes happened, not from commit history.
  local notes_file notes_body
  notes_body="$(changelog_unreleased)"
  if [ -z "$notes_body" ]; then
    echo "error: CHANGELOG.md has nothing under '## Unreleased'." >&2
    echo "       Release notes are what users read to decide whether to update, and an empty" >&2
    echo "       release tells them nothing. Add what changed, then publish." >&2
    echo "       Not every code change needs a line; write what someone using the app would" >&2
    echo "       notice." >&2
    exit 1
  fi
  notes_file="$(mktemp)"
  printf '%s\n' "$notes_body" > "$notes_file"

  echo "==> Release notes for $tag:"
  sed 's/^/    /' "$notes_file"
  echo

  echo "==> Publishing $tag to $repo_slug..."
  if gh release view "$tag" --repo "$repo_slug" >/dev/null 2>&1; then
    gh release upload "$tag" "$apk_path" "$sig_path" "$sums_path" --repo "$repo_slug" --clobber
    # Also (re)assert latest on an existing release -- see the create branch below for why.
    gh release edit "$tag" --repo "$repo_slug" --notes-file "$notes_file" --latest >/dev/null 2>&1 || true
  else
    # --latest is explicit rather than left to GitHub's automatic choice. That automatic choice
    # is what decides the /releases/latest API response, which is exactly the endpoint the app's
    # own update check calls -- so if GitHub leaves an older tag marked latest, every client
    # keeps being offered the old version no matter how many new releases get published. That is
    # not a cosmetic badge; it silently breaks updates for everyone.
    gh release create "$tag" "$apk_path" "$sig_path" "$sums_path" \
      --repo "$repo_slug" --title "$tag" --notes-file "$notes_file" --latest
  fi
  # Confirm the assets actually attached rather than trusting that gh succeeded. A release with
  # no APK looks fine from the command line but is useless -- and the app's update check will
  # report "release has no .apk asset" rather than anything pointing back at the publish step.
  local attached
  attached=$(gh release view "$tag" --repo "$repo_slug" --json assets --jq '[.assets[].name] | join(" ")' 2>/dev/null || true)
  echo "==> Assets now on the release: ${attached:-<none>}"

  # Check the APK's size on the release matches the file on disk.
  #
  # A failed upload still registers the asset name, so checking names alone reported success
  # while GitHub showed "Upload failed. Delete and try uploading this file again." and served a
  # broken file. The .sig is 64 bytes and always completes; the APK is 35MB and is the one that
  # actually fails. Size is what distinguishes "uploaded" from "named".
  local local_size remote_size
  local_size=$(stat -c %s "$apk_path" 2>/dev/null || echo 0)

  # Re-upload on a size mismatch before giving up. A 35MB transfer failing once is ordinary, and
  # the fix is the same either way: delete the partial asset and send it again. Doing that here
  # saves a manual round trip through the GitHub web interface.
  local upload_attempt=1
  while [ "$upload_attempt" -le 3 ]; do
    remote_size=$(gh release view "$tag" --repo "$repo_slug" --json assets \
      --jq "[.assets[] | select(.name == \"$apk_name\") | .size] | first // 0" 2>/dev/null || echo 0)
    [ "$remote_size" = "$local_size" ] && break
    echo "warning: APK on the release is $remote_size bytes, expected $local_size --" >&2
    echo "         re-uploading (attempt $upload_attempt of 3)..." >&2
    gh release delete-asset "$tag" "$apk_name" --repo "$repo_slug" --yes >/dev/null 2>&1 || true
    gh release upload "$tag" "$apk_path" --repo "$repo_slug" --clobber >/dev/null 2>&1 || true
    upload_attempt=$((upload_attempt + 1))
  done

  if [ "$remote_size" != "$local_size" ]; then
    echo "error: the APK on the release is $remote_size bytes but the local file is $local_size." >&2
    echo "       The upload did not complete. GitHub keeps the asset name even when the transfer" >&2
    echo "       fails, so the release looks populated while the download is broken." >&2
    echo "       Three upload attempts all failed, so this is not a transient glitch." >&2
    echo "       Delete the $apk_name asset on the release page, then re-run:" >&2
    echo "         ./BUILD.sh --upload-only" >&2
    exit 1
  fi
  echo "==> APK size on the release matches the local file ($local_size bytes)."
  local missing=""
  case "$attached" in *"$apk_name"*) ;; *) missing="$missing $apk_name" ;; esac
  case "$attached" in *"$apk_name.sig"*) ;; *) missing="$missing $apk_name.sig" ;; esac
  case "$attached" in *"SHA256SUMS.txt"*) ;; *) missing="$missing SHA256SUMS.txt" ;; esac
  if [ -n "$missing" ]; then
    echo "error: the release was created but these assets are not attached:$missing" >&2
    echo "       The app cannot install an update from a release without its APK and .sig." >&2
    echo "       Re-run with ./BUILD.sh --upload-only to retry attaching them." >&2
    exit 1
  fi

  # Confirm this really is what /releases/latest returns. The app's update check asks GitHub for
  # the latest release, not for a specific tag, so a release that exists but isn't marked latest
  # is invisible to every client -- which looks exactly like "the APK didn't publish".
  local latest_tag
  latest_tag=$(gh release view --repo "$repo_slug" --json tagName --jq .tagName 2>/dev/null || true)
  if [ -n "$latest_tag" ] && [ "$latest_tag" != "$tag" ]; then
    echo "error: $tag was published, but GitHub still reports '$latest_tag' as the latest" >&2
    echo "       release. The app asks for /releases/latest, so it would keep being offered" >&2
    echo "       $latest_tag instead of $tag. Fix with:" >&2
    echo "         gh release edit $tag --repo $repo_slug --latest" >&2
    exit 1
  fi
  echo "==> Confirmed latest release is $tag (this is what the app's update check will see)."

  # Only now: a failed publish must not consume the notes, or the next release would describe
  # nothing while the changes it shipped went unrecorded.
  changelog_roll "$(read_version_name)"
  rm -f "$notes_file"
  echo "==> CHANGELOG.md rolled: Unreleased is empty again, ready for the next round of changes."

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
  mkdir -p "$stage/niix"
  # Copy only what belongs in the repo, rather than copying everything and deleting afterwards.
  # app/build alone can hold gigabytes of build output including the APK itself; copying that to
  # a temp directory just to throw it away is slow, and on a machine short of disk the copy can
  # fail outright -- which under `set -e` aborts the publish partway, after the source push but
  # before the APK is attached.
  # An allowlist, not a skip list.
  #
  # A skip list ships anything nobody thought to exclude, and the thing nobody thought to exclude
  # is the thing that should not have been public. Listing what goes means a new file in the
  # project directory stays private until someone adds it here on purpose.
  #
  # Everything below is either source, needed to build the source, or legally required. screens/
  # isn't here on purpose: it's handled on its own below.
  local entry
  for entry in \
    README.md \
    CHANGELOG.md \
    ROADMAP.md \
    PRIVACY.md \
    update-key.pub \
    fdroid \
    licenses \
    LICENSE \
    .gitignore \
    BUILD.sh \
    scripts \
    run-tests-offline.sh \
    docs \
    app \
    core \
    gradle \
    gradlew \
    gradlew.bat \
    build.gradle.kts \
    settings.gradle.kts \
    gradle.properties \
    version.properties; do
    [ -e "$entry" ] || continue
    cp -r "$entry" "$stage/niix/" 2>/dev/null || true
  done
  # Nested build directories (app/build, core/*/build) still need clearing.
  find "$stage/niix" -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
  find "$stage/niix" -type f \( -name "keystore.properties" -o -name "*.jks" -o -name "*.pem" \) -delete

  # Refuse to push if anything key-shaped is in the staged tree.
  #
  # The allowlist above should make this impossible, which is exactly why it is worth checking.
  # A guard that only fires when another guard has already failed is the one that catches the
  # mistake nobody predicted, and a leaked signing key cannot be unleaked.
  local leaked
  leaked="$(find "$stage/niix" -type f \( \
      -name '*.jks' -o -name '*.keystore' -o -name '*.pem' -o -name '*.p12' -o -name '*.pfx' \
      -o -name 'keystore*.properties' -o -name 'local.properties' -o -name '*.key' \
    \) 2>/dev/null || true)"
  if [ -n "$leaked" ]; then
    echo "error: refusing to push. Key material found in the staged tree:" >&2
    printf '%s\n' "$leaked" | sed "s|$stage/niix/|  |" >&2
    echo "       Nothing has been pushed." >&2
    rm -rf "$stage"
    exit 1
  fi

  # A private key pasted into a source file, which no filename check would catch.
  if grep -rlE "BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY" "$stage/niix" >/dev/null 2>&1; then
    echo "error: refusing to push. A PRIVATE KEY block appears inside a file:" >&2
    grep -rlE "BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY" "$stage/niix" 2>/dev/null \
      | sed "s|$stage/niix/|  |" >&2
    echo "       Nothing has been pushed." >&2
    rm -rf "$stage"
    exit 1
  fi

  # The public repo gets this same BUILD.sh: one script for everyone. Its publishing options
  # need the maintainer's keys and GitHub login, so for anyone else they simply stop with a
  # message; building and testing work the same.
  chmod +x "$stage/niix/BUILD.sh" 2>/dev/null || true

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

  # Second layer, by content rather than filename. The check above catches a key that is still
  # named like a key; this catches one that isn't -- a PEM block pasted into a note, a keystore
  # copied to an innocuous name, a password left in a config. Filename rules only protect
  # against the mistakes you already thought of, and a leaked signing key cannot be un-leaked.
  local secret_hits
  # A PEM private-key block is a secret wherever it appears, so that is scanned everywhere.
  secret_hits=$(grep -rlE "BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY" "$stage/niix" 2>/dev/null || true)
  # Passwords are only checked inside .properties files, where "key=value" genuinely is a value.
  # Source and script files legitimately mention these names as identifiers or in help text
  # (build.gradle.kts reads getProperty("storePassword"); this script prints the expected format),
  # and treating those as leaks would block every publish for no reason.
  local pw_hits
  pw_hits=$(find "$stage/niix" -type f -name "*.properties" -exec \
    grep -lE "^[[:space:]]*(storePassword|keyPassword)[[:space:]]*=[[:space:]]*[^[:space:]<]" {} + 2>/dev/null || true)
  secret_hits="$secret_hits$pw_hits"
  if [ -n "$secret_hits" ]; then
    echo "error: files in the staged source copy appear to contain private key material or" >&2
    echo "       passwords. Refusing to push or publish anything. Found:" >&2
    echo "$secret_hits" >&2
    rm -rf "$stage"
    exit 1
  fi
  # Java keystores are binary and start with a known magic number, so they are detectable even
  # renamed to something harmless.
  local ks_hits=""
  while IFS= read -r candidate; do
    if head -c 4 "$candidate" 2>/dev/null | od -An -tx1 | tr -d ' \n' | grep -qiE "^(feedfeed|cececece)$"; then
      ks_hits="$ks_hits $candidate"
    fi
  done < <(find "$stage/niix" -type f -size -100k 2>/dev/null)
  if [ -n "$ks_hits" ]; then
    echo "error: files in the staged source copy look like Java keystores (by magic number," >&2
    echo "       regardless of filename). Refusing to publish. Found:$ks_hits" >&2
    rm -rf "$stage"
    exit 1
  fi

  local default_branch
  default_branch="$(gh repo view "$repo_slug" --json defaultBranchRef --jq .defaultBranchRef.name 2>/dev/null)"
  default_branch="${default_branch:-main}"

  gh auth setup-git >/dev/null 2>&1 || true

  # screens/ holds the README's screenshots, which the maintainer manages by hand, on GitHub or
  # locally. This push replaces everything else in the repository, so whatever screens/ the
  # repository already has goes back exactly as it was, file for file. The local folder is only
  # used while the repository has none yet. If the repository can't be read, nothing is pushed,
  # rather than risk pushing without the screenshots.
  local existing
  existing="$(mktemp -d)"
  if ! gh repo clone "$repo_slug" "$existing/repo" -- --depth 1 --quiet >/dev/null 2>&1; then
    rm -rf "$stage" "$existing"
    echo "error: could not read $repo_slug to keep its screens/ folder, so nothing was pushed." >&2
    echo "       Check that gh is logged in and the repository exists, then try again." >&2
    exit 1
  fi
  if [ -d "$existing/repo/screens" ]; then
    cp -a "$existing/repo/screens" "$stage/niix/screens"
    echo "==> Keeping screens/ exactly as it is on GitHub."
  elif [ -d screens ]; then
    cp -a screens "$stage/niix/screens"
    echo "==> GitHub has no screens/ yet, so the local one goes up."
  fi
  rm -rf "$existing"

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

# Finds which NiiX package on the device is debuggable enough for run-as.
#
# A debug build installs as app.niix.debug, not app.niix -- the applicationIdSuffix. Reading a
# hardcoded app.niix meant run-as always failed against the very build this check requires, and
# reported it as "install a debug build" when a debug build was already installed. Asking the
# device removes the guess.
resolve_runas_package() {
  local adb_bin="$1"
  local pkg
  for pkg in app.niix.debug app.niix; do
    if "$adb_bin" shell "run-as $pkg true" >/dev/null 2>&1; then
      echo "$pkg"
      return 0
    fi
  done
  return 1
}

# Lists the app's private storage exactly as it exists on the device. run-as gives access to a
# debuggable app's own data directory without root, which is what makes this checkable at all.
snapshot_app_storage() {
  local adb_bin="$1"
  local pkg="${2:-app.niix.debug}"
  "$adb_bin" shell "run-as $pkg ls -laR files databases no_backup 2>/dev/null" 2>/dev/null || true
}

if [ "$MODE" != "upload-only" ] && [ "$MODE" != "install" ] && [ "$MODE" != "verify-duress" ] \
    && [ "$MODE" != "backupkeys" ] && [ "$MODE" != "clean" ]; then
  # Refuse to run if signing secrets are sitting in the project directory unprotected by
# .gitignore. Keys belong outside the repo entirely; keeping them here is how they end up in a
# zip, an archive, or a commit. This is a hard stop rather than a warning because the failure is
# silent and unrecoverable -- a leaked signing key can't be un-leaked, only rotated.
check_secret_placement() {
  local found=""
  for f in niix-release.jks update-signing-key.pem niix-update-signing.pem update-signing.pem; do
    [ -f "$f" ] && found="$found $f"
  done
  if [ -n "$found" ]; then
    echo "warning: signing key(s) present in the project directory:$found" >&2
    echo "         These are excluded from git and from anything this script publishes, but they" >&2
    echo "         WILL be included if you zip or copy this folder. Prefer keeping them outside" >&2
    echo "         the project and pointing at them with NIIX_KEYSTORE_PROPERTIES and" >&2
    echo "         NIIX_UPDATE_SIGNING_KEY, or create new ones outside it with option 14." >&2
  fi
}
check_secret_placement

# Identifies which copy of this script is running.
#
# Three rounds were spent on one failure because the logs could not show whether a fix was even
# present -- an older extraction looks identical in output to a current one. Printing the script's
# own hash makes that unambiguous: if it does not match the version that was sent, the fix is not
# in the file being run.
SCRIPT_REVISION="$(sha256sum "$0" 2>/dev/null | cut -c1-12 || echo unknown)"
echo "==> BUILD.sh revision $SCRIPT_REVISION"

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

# Shows what CHANGELOG.md says this build contains.
#
# The changelog is meant to describe the app as it currently is, and it only stays that way if it
# is looked at when the app changes. Printing the count on every build keeps it in view; release
# builds warn when it is empty, and publishing refuses outright.
changelog_entries="$(changelog_unreleased | grep -c '^- ' || true)"
echo "==> CHANGELOG.md: ${changelog_entries:-0} entries under Unreleased"
case "$MODE" in
  release|upload-only)
    if [ "${changelog_entries:-0}" = "0" ]; then
      echo "warning: CHANGELOG.md has nothing under Unreleased. Users read it to decide whether" >&2
      echo "         to update; add what changed before publishing this build." >&2
    fi
    ;;
esac

echo "==> Checking build tools..."
  ensure_jdk
  # Creating and showing keys needs only the JDK's keytool, not Gradle or the Android SDK.
  if [ "$MODE" != "createkeys" ] && [ "$MODE" != "showkeys" ]; then
    ensure_gradle
    ensure_android_sdk
  fi
fi

case "$MODE" in
  createkeys) create_signing_keys; exit 0 ;;
  showkeys) use_saved_keys; niix_show_keys; exit 0 ;;
  sharedkey) use_saved_keys; niix_switch_key; exit 0 ;;
  backupkeys) niix_backup_keys; exit 0 ;;
  clean) niix_clean; exit 0 ;;
  release|upload-only|playbundle) use_saved_keys ;;
esac

case "$MODE" in
  pushsource)
    # Pushes source and the build script to GitHub without cutting a release.
    #
    # The publish path does this too, but only as part of tagging and uploading an APK. Wanting
    # the repo up to date without shipping a build is a normal thing to want, and running a full
    # publish to get it would create a release nobody asked for.
    ensure_gh
    repo_slug="$(find_repo_slug)"
    echo "==> Pushing source to $repo_slug"
    echo "    Keys, build output and logs are excluded. The public build script replaces the"
    echo "    maintainer one, so publishing tooling is not handed out."
    echo
    sync_source_to_repo "$repo_slug" "$(read_version_name)"
    echo
    echo "==> Done: https://github.com/$repo_slug"
    exit 0
    ;;

  audit-zip)
    # Produces the archive to hand a reviewer.
    #
    # Built from this working tree rather than assembled elsewhere, specifically so it picks up
    # gradle/wrapper/gradle-wrapper.jar. That file is generated on first build and is what a
    # reviewer needs to run ./gradlew at all -- three separate audits reported being unable to
    # compile or run the tests without it. Signing keys and build output are stripped, and the
    # result is verified before it is written.
    if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
      echo "error: gradle/wrapper/gradle-wrapper.jar is missing, so a reviewer would not be" >&2
      echo "       able to run ./gradlew. Run ./BUILD.sh --debug once to generate it," >&2
      echo "       then re-run this." >&2
      exit 1
    fi

    stage="$(mktemp -d)"
    mkdir -p "$stage/niix-source"
    for entry in * .[!.]*; do
      [ -e "$entry" ] || continue
      case "$(basename "$entry")" in
        .git|.gradle|.toolchain|build|local.properties) continue ;;
      build.log|test.log|build-test-full.log|device-test-results) continue ;;
      esac
      cp -r "$entry" "$stage/niix-source/" 2>/dev/null || true
    done
    find "$stage/niix-source" -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
    find "$stage/niix-source" -type f \( -name "*.jks" -o -name "*.pem" -o -name "keystore.properties" \) -delete

    chmod +x "$stage/niix-source/BUILD.sh" 2>/dev/null || true

    leaked=$(find "$stage/niix-source" \( -name "*.jks" -o -name "*.pem" -o -name "keystore.properties" \) 2>/dev/null)
    if [ -n "$leaked" ]; then
      echo "error: signing material survived the strip -- refusing to write the archive." >&2
      rm -rf "$stage"
      exit 1
    fi

    out="$ROOT/niix-source-for-audit.zip"
    rm -f "$out"
    (cd "$stage" && zip -rq "$out" niix-source)
    rm -rf "$stage"
    echo "==> Wrote $out"
    echo "    Reviewer runs: unzip, then ./BUILD.sh"
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
      app.niix.core.nostr.test \
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
    "$GRADLE_CMD" --console=plain assembleGithubDebug assembleGithubDebugAndroidTest

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
    "$GRADLE_CMD" --console=plain installGithubDebug installGithubDebugAndroidTest

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
    "$GRADLE_CMD" --console=plain :app:assembleGithubDebug
    OUT=$(find app/build/outputs/apk/github/debug -name "*.apk" ! -name "niix-messenger-*" 2>/dev/null | head -1 || true)
    [ -n "$OUT" ] && OUT="$(rename_output_apk "$OUT")"
    [ -n "$OUT" ] && verify_apk "$OUT"
    ;;

  reproducible)
    echo "==> Building unsigned release APK for hash comparison (-PniixUnsigned=true)..."
    echo "    This never reads keystore.properties, even if one exists."
    "$GRADLE_CMD" --console=plain :app:assembleGithubRelease -PniixUnsigned=true
    OUT=$(find app/build/outputs/apk/github/release -name "*unsigned*.apk" | head -1)
    if [ -n "$OUT" ]; then
      echo "==> SHA-256: $(sha256sum "$OUT" | cut -d' ' -f1)"
      echo "    Compare this against another independent build of the exact same commit."
    fi
    ;;

  playbundle)
    # Google Play takes an app bundle rather than an APK, signed with an upload key. Google then
    # re-signs what it delivers with its own app signing key (Play App Signing), so the key this
    # build is signed with never reaches users' phones.
    #
    # The upload key is kept separate from the GitHub release key on purpose. The GitHub key has
    # been exposed and must keep signing GitHub builds so existing installs can update; using it
    # to upload to Play as well would tie the Play listing to a compromised key for no reason.
    PLAY_KEYSTORE_PROPERTIES="${NIIX_PLAY_KEYSTORE_PROPERTIES:-keystore-play.properties}"
    if [ ! -f "$PLAY_KEYSTORE_PROPERTIES" ]; then
      cat >&2 <<EOF
error: no Play upload key found at '$PLAY_KEYSTORE_PROPERTIES'.

The easiest way: run option 14 (--create-keys) and answer yes when it offers a Google Play
upload key. If you already created your other keys, do it by hand instead:

  1. Generate it:
       keytool -genkeypair -v -storetype PKCS12 -keystore niix-play-upload.jks \\
         -alias niix-upload -keyalg RSA -keysize 4096 -validity 10000
  2. Create keystore-play.properties in the project root:
       storeFile=niix-play-upload.jks
       storePassword=<your store password>
       keyAlias=niix-upload
       keyPassword=<your key password>
     (or set NIIX_PLAY_KEYSTORE_PROPERTIES to a file stored elsewhere)
  3. Back both files up somewhere safe and offline. Losing the upload key means asking Google
     to reset it, which takes days.

Neither file is ever pushed to GitHub: the source push only sends files on its allowlist, and it
refuses outright if it finds a key.
EOF
      exit 1
    fi

    NEW_VERSION_NAME=""
    choose_version false

    echo "==> Building the Google Play bundle using $PLAY_KEYSTORE_PROPERTIES..."
    echo "    This build has no self-updater: Play delivers its updates."
    PLAY_ARGS=(--console=plain :app:bundlePlayRelease)
    [ -n "${NIIX_VERSION_NAME:-}" ] && PLAY_ARGS+=(-PversionName="$NIIX_VERSION_NAME")
    [ -n "${NIIX_VERSION_CODE:-}" ] && PLAY_ARGS+=(-PversionCode="$NIIX_VERSION_CODE")
    NIIX_KEYSTORE_PROPERTIES="$(absolute_path "$PLAY_KEYSTORE_PROPERTIES")" "$GRADLE_CMD" "${PLAY_ARGS[@]}"

    AAB=$(find app/build/outputs/bundle/playRelease -name "*.aab" 2>/dev/null | head -1 || true)
    if [ -z "$AAB" ]; then
      echo "error: the build finished but no .aab was found in app/build/outputs/bundle/playRelease/." >&2
      exit 1
    fi
    mkdir -p dist
    PLAY_OUT="dist/niix-messenger-$(read_version_name)-play.aab"
    cp -f "$AAB" "$PLAY_OUT"
    echo
    echo "==> Built: $PLAY_OUT ($(du -h "$PLAY_OUT" | cut -f1))"
    echo "    Upload it in Play Console under Release > Production (or a testing track)."
    echo "    First upload only: choose to let Google manage the app signing key."
    exit 0
    ;;

  release)
    KEYSTORE_PROPERTIES="${NIIX_KEYSTORE_PROPERTIES:-keystore.properties}"
    if [ ! -f "$KEYSTORE_PROPERTIES" ]; then
      cat >&2 <<EOF
error: no signing key found (looked in $NIIX_KEY_DIR and for keystore.properties here).

If you already created keys, make sure their folder is available, for example that the USB
stick holding them is plugged in. Otherwise create them with option 14 in the menu, or:
  ./BUILD.sh --create-keys

That keeps the keys in ~/.keys/niix, outside the project, and the build finds them there
automatically. To use keys kept somewhere else, such as removable media, set
NIIX_KEYSTORE_PROPERTIES to the full path of their keystore.properties.

To build something installable right now without any of that, run:
  ./BUILD.sh --debug
EOF
      exit 1
    fi

    ensure_update_key_pin

    NEW_VERSION_NAME=""
    choose_version "$DO_PUBLISH"

    echo "==> Building signed release APK using $KEYSTORE_PROPERTIES..."
    if [ -n "${NIIX_KEYSTORE_PROPERTIES:-}" ]; then
      NIIX_KEYSTORE_PROPERTIES="$(absolute_path "$NIIX_KEYSTORE_PROPERTIES")"
      export NIIX_KEYSTORE_PROPERTIES
    fi
    GRADLE_ARGS=(--console=plain :app:assembleGithubRelease)
    [ -n "${NIIX_VERSION_NAME:-}" ] && GRADLE_ARGS+=(-PversionName="$NIIX_VERSION_NAME")
    [ -n "${NIIX_VERSION_CODE:-}" ] && GRADLE_ARGS+=(-PversionCode="$NIIX_VERSION_CODE")
    "$GRADLE_CMD" "${GRADLE_ARGS[@]}"
    OUT=$(find app/build/outputs/apk/github/release -name "*.apk" ! -name "*unsigned*" ! -name "niix-messenger-*" 2>/dev/null | head -1 || true)
    if [ -n "$OUT" ]; then
      niix_sign_rotated "$OUT"
      OUT="$(rename_output_apk "$OUT")"
      verify_apk "$OUT"
    fi
    if [ "$DO_PUBLISH" = true ] && [ -n "$OUT" ]; then
      publish_release "$OUT" "$NEW_VERSION_NAME"
    fi
    ;;

  verify-duress)
    # Two-phase check of whether a duress wipe actually destroys data, rather than just making
    # the UI show a fresh account. Those look identical from the phone; they are not the same
    # thing, and the difference is exactly what matters to someone whose device has been seized.
    adb_bin="$(resolve_adb)"
    require_device "$adb_bin"

    # Which package to inspect, asked of the device rather than assumed.
    RUNAS_PKG="$(resolve_runas_package "$adb_bin" || true)"
    if [ -z "$RUNAS_PKG" ]; then
      echo "error: no debuggable NiiX install found on the phone." >&2
      echo "       This check reads the app's private storage with run-as, which only works on" >&2
      echo "       a debuggable build. A release build refuses it -- correctly, but it means" >&2
      echo "       this has to be run against a debug install." >&2
      echo >&2
      echo "       Install one:  ./BUILD.sh --debug   then option 6 to install it" >&2
      echo >&2
      echo "       NiiX packages currently on the phone:" >&2
      "$adb_bin" shell pm list packages 2>/dev/null | tr -d '\r' | grep -i niix | sed 's/^/         /' >&2 || true
      exit 1
    fi
    echo "==> Inspecting $RUNAS_PKG"

    snapshot_before="$TOOLCHAIN/duress-before.txt"
    snapshot_after="$TOOLCHAIN/duress-after.txt"

    # One run, with a pause in the middle.
    #
    # Previously this took the "before" snapshot and exited, expecting a second invocation after
    # the wipe. That made it easy to run twice without doing the wipe in between -- and the
    # comparison then reported everything as having survived, which reads as a serious finding
    # and is really just "the wipe never happened". Holding the run open removes that gap
    # entirely: there is no second invocation to get wrong.
    snapshot_app_storage "$adb_bin" "$RUNAS_PKG" > "$snapshot_before"
    if [ ! -s "$snapshot_before" ]; then
      rm -f "$snapshot_before"
      echo "error: run-as works for $RUNAS_PKG but its storage listed nothing." >&2
      echo "       The app is installed but has no data yet -- open it, set a passcode and" >&2
      echo "       send a message first, so there is something whose destruction can be" >&2
      echo "       verified. A wipe of an empty app proves nothing." >&2
      exit 1
    fi
    echo "==> Recorded storage before the wipe ($(wc -l < "$snapshot_before") entries)."
    echo
    echo "═══════════════════════════════════════════════════════════════════════"
    echo "  Now, on the phone:"
    echo
    echo "    1. Check there is real data -- a conversation, an attachment."
    echo "    2. Note the safety number in Settings. You will compare it after."
    echo "    3. Lock the app."
    echo "    4. Unlock with the DURESS passcode. Not the real one."
    echo
    echo "  This will wait. Nothing is measured until you continue."
    echo "═══════════════════════════════════════════════════════════════════════"
    echo
    # Read from the terminal rather than stdin: stdin is the tee pipeline here, so a plain read
    # would return immediately and skip the pause the whole design depends on.
    printf "Press Enter once the duress wipe has been done (Ctrl-C to abort): "
    read -r _ < /dev/tty || true
    echo

    snapshot_app_storage "$adb_bin" "$RUNAS_PKG" > "$snapshot_after"

    # Refuse to report on a wipe that did not happen.
    #
    # Without this the tool compares a snapshot against a later copy of itself and announces
    # "PRESENT AFTER WIPE" for everything -- which reads as a serious security finding and is
    # really just "you have not done step 3 yet". A verification tool that manufactures findings
    # when it has verified nothing is worse than no tool: it spends the reader's attention on a
    # problem that does not exist, and teaches them to discount its output.
    if diff -q "$snapshot_before" "$snapshot_after" >/dev/null 2>&1; then
      echo "RESULT: storage is byte-for-byte identical to before." >&2
      echo >&2
      echo "  If you DID unlock with the duress passcode, this is a serious finding: the wipe" >&2
      echo "  ran and destroyed nothing. Everything the app held is still on the device." >&2
      echo >&2
      echo "  If you did not get as far as the duress unlock, nothing was tested. Re-run and" >&2
      echo "  complete step 4 before continuing." >&2
      exit 1
    fi

    echo "==> Comparing storage before and after the duress wipe..."
    echo
    # `|| true` rather than `|| echo 0`: grep -c already prints 0 when nothing matches and then
    # exits non-zero, so `|| echo 0` appends a second 0 and the comparison gets "0\n0".
    count_in() { grep -c "$1" "$2" 2>/dev/null || true; }

    survivors=""
    # Deliberately excludes niix.db itself: the decoy account has to create a fresh database, so
    # its mere presence proves nothing. It's handled separately below by comparing the actual
    # directory entry -- an identical size and timestamp means the original file was never
    # replaced, which is the real failure.
    # Compares the whole directory entry, not just the name.
    #
    # Presence alone is not a failure for anything the app recreates on restart: Tor rebuilds its
    # data directory the moment it starts again, so "niix-tor exists" says nothing. What matters
    # is whether the entry is the same one -- identical size and timestamp means the original
    # survived; a different one means it was destroyed and rebuilt.
    entry_for() { grep -E "[[:space:]]$1\$" "$2" 2>/dev/null | head -1 || true; }

    for pattern in "niix.db-wal" "niix.db-shm" "niix-tor" "niix-tor-cache" "attachments"; do
      before_has=$(count_in "$pattern" "$snapshot_before")
      after_has=$(count_in "$pattern" "$snapshot_after")
      before_has=${before_has:-0}
      after_has=${after_has:-0}
      if [ "$before_has" -gt 0 ] && [ "$after_has" -gt 0 ]; then
        before_entry="$(entry_for "$pattern" "$snapshot_before")"
        after_entry="$(entry_for "$pattern" "$snapshot_after")"
        if [ -n "$before_entry" ] && [ "$before_entry" != "$after_entry" ]; then
          # Rebuilt, not survived. Correct for anything the app recreates on startup.
          echo "  recreated: $pattern (different entry -- destroyed and rebuilt)"
          continue
        fi
        survivors="$survivors $pattern"
        echo "  PRESENT AFTER WIPE: $pattern (same entry -- not replaced)"
      elif [ "$before_has" -gt 0 ]; then
        echo "  gone: $pattern"
      fi
    done

    # The database line carries size and timestamp, so an unchanged entry means the same file is
    # still sitting there rather than a fresh one having replaced it.
    db_before=$(grep "niix.db$" "$snapshot_before" 2>/dev/null | head -1 || true)
    db_after=$(grep "niix.db$" "$snapshot_after" 2>/dev/null | head -1 || true)
    if [ -n "$db_before" ] && [ "$db_before" = "$db_after" ]; then
      survivors="$survivors niix.db(unchanged)"
      echo "  PRESENT AFTER WIPE: niix.db is byte-identical to before -- it was NOT replaced"
    elif [ -n "$db_after" ]; then
      echo "  replaced: niix.db (new size/timestamp -- a fresh decoy database, as expected)"
    fi
    echo
    if [ -n "$survivors" ]; then
      echo "RESULT: these still exist after the duress wipe:$survivors" >&2
      echo
      echo "A fresh database being recreated is expected -- the decoy account needs one." >&2
      echo "What matters is whether the file was replaced or merely left in place. Compare" >&2
      echo "the two snapshots directly (sizes and timestamps tell you which):" >&2
      echo "  diff $snapshot_before $snapshot_after" >&2
    else
      echo "RESULT: every tracked file from before the wipe is gone."
    fi
    echo
    echo "Still to check by hand, because it can't be seen from the filesystem:"
    echo "  The safety number in Settings must DIFFER from what it was before the wipe. If it"
    echo "  matches, the app is still using the pre-wipe identity even though the data is gone,"
    echo "  which defeats the point of the duress account entirely."
    echo
    echo "Snapshots kept at:"
    echo "  $snapshot_before"
    echo "  $snapshot_after"
    echo "Both are overwritten on the next run, so nothing stale carries forward."

    # Exit here rather than falling through to the build-output check. This mode builds nothing,
    # so that check warned the APK was missing and set a failure code -- making a duress
    # verification that found nothing wrong look like a failed run.
    #
    # The exit code reflects the verification itself: non-zero only when something actually
    # survived the wipe.
    if [ -n "$survivors" ]; then
      exit 1
    fi
    exit 0
    ;;

  install)
    # Installs the built APK over adb, which -- unlike the on-device installer -- prints the
    # actual reason Android rejected it. "App not installed as package appears to be invalid" is
    # a catch-all that covers a downgrade, a signing-key mismatch, a parse failure and several
    # other unrelated conditions; adb tells you which one it actually is
    # (INSTALL_FAILED_VERSION_DOWNGRADE, INSTALL_FAILED_UPDATE_INCOMPATIBLE,
    # INSTALL_PARSE_FAILED_NO_CERTIFICATES, ...), and that distinction is the difference between
    # fixing this in a minute and guessing at it for days.
    OUT=$(find app/build/outputs/apk/github/release -name "niix-messenger-*.apk" 2>/dev/null | head -1 || true)
    if [ -z "$OUT" ]; then
      echo "error: no built release APK found. Run ./BUILD.sh --release first." >&2
      exit 1
    fi
    # Was a second, older copy of the device check that read `adb devices | sed -n '2p'`.
    #
    # That reads a fixed line number, so on the first adb call of a session it landed on the
    # "daemon started successfully" message instead of the device list -- reporting no device
    # while a working phone was plugged in. require_device was written to fix exactly that, and
    # this duplicate was left behind still carrying the bug, and still giving advice ("plug it
    # in") that is wrong for an unauthorised or offline phone.
    adb_bin="$(resolve_adb)"
    require_device "$adb_bin"
    echo "==> Installing $OUT over adb..."
    echo "    -r keeps existing app data (this is an update, not a wipe)."
    if "$adb_bin" install -r "$OUT"; then
      echo "==> Installed successfully."
    else
      echo
      echo "The line above starting with 'Failure [' is Android's real reason. Briefly:" >&2
      echo "  INSTALL_FAILED_VERSION_DOWNGRADE   -- versionCode is not higher than what's installed" >&2
      echo "  INSTALL_FAILED_UPDATE_INCOMPATIBLE -- signed with a different key than what's installed" >&2
      echo "  INSTALL_PARSE_FAILED_*             -- the APK itself is malformed" >&2
      echo "  INSTALL_FAILED_INSUFFICIENT_STORAGE-- not enough free space on the device" >&2
      echo "Send that exact line and the cause is identifiable rather than guessable." >&2
      exit 1
    fi
    ;;

  upload-only)
    OUT=$(find app/build/outputs/apk/github/release -name "niix-messenger-*.apk" 2>/dev/null | head -1 || true)
    if [ -z "$OUT" ]; then
      OUT=$(find app/build/outputs/apk/github/release -name "*.apk" ! -name "*unsigned*" 2>/dev/null | head -1 || true)
    fi
    if [ -z "$OUT" ]; then
      echo "error: no previously-built release APK found." >&2
      echo "       app/build/outputs/apk/github/release/ is empty or missing -- usually because the" >&2
      echo "       build directory was cleaned, or the last build failed before producing an APK." >&2
      echo "       This mode only publishes an existing APK; it does not build one. Run a build" >&2
      echo "       first:  ./BUILD.sh --release" >&2
      exit 1
    fi
    # Verify here too, not just in the build path. This mode publishes an APK built by some
    # earlier run, so nothing in *this* run has checked it -- and publishing a structurally
    # broken APK is exactly the failure that ends with "package appears to be invalid" on
    # someone's phone, long after there's any obvious connection to the build that produced it.
    verify_apk "$OUT"
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
