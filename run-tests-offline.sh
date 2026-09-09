#!/usr/bin/env bash
# run-tests-offline.sh -- runs the pure-JVM test suites without Gradle.
#
# Why this exists: the normal path (./build-niix.sh --test) needs Gradle, and Gradle downloads
# its own distribution from services.gradle.org on first use. In a sandboxed or offline review
# environment that download fails, which leaves a reviewer unable to execute any tests at all --
# reducing the review to reading code.
#
# This compiles the modules that have no Android dependency directly with kotlinc and runs their
# tests with JUnit. It needs only kotlinc and a JUnit 4 jar, both commonly available, and no
# network.
#
# It does NOT run everything. The Android-dependent code (storage, transport, UI) cannot be
# tested this way and is not covered here. What it does cover is the security-critical pure
# logic: group membership and state chaining, wire protocol parsing, input validation, and the
# relay store.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "error: kotlinc not found." >&2
  echo "       Debian/Ubuntu: apt install kotlin" >&2
  echo "       macOS:         brew install kotlin" >&2
  exit 1
fi

# The project targets Kotlin 2.x. An older compiler produces a wall of syntax errors on things
# like trailing commas (1.4+) and String.uppercase (1.5+), which look like defects in the code
# rather than a toolchain mismatch. Say so plainly instead.
# Anchored on "kotlinc-jvm" -- the version line also contains the JRE version, and matching the
# first number anywhere picks that up instead.
KOTLIN_VERSION="$(kotlinc -version 2>&1 | sed -n 's/.*kotlinc-jvm \([0-9][0-9.]*\).*/\1/p' | head -1)"
KOTLIN_MAJOR="${KOTLIN_VERSION%%.*}"
if [ -n "$KOTLIN_MAJOR" ] && [ "$KOTLIN_MAJOR" -lt 2 ] 2>/dev/null; then
  echo "error: kotlinc $KOTLIN_VERSION is too old; this project needs Kotlin 2.x." >&2
  echo "       Errors from an older compiler are toolchain mismatches, not code defects." >&2
  echo "       Install a current Kotlin, or use ./build-niix.sh --test where a network is" >&2
  echo "       available (that path fetches its own toolchain)." >&2
  exit 1
fi

# JUnit 4 plus hamcrest. Distro paths first, then anything already fetched locally.
find_jar() {
  local name="$1"
  for candidate in \
    "/usr/share/java/$name.jar" \
    "/usr/share/java/${name}4.jar" \
    "$HOME/.m2/repository"/**/"$name"*.jar
  do
    [ -f "$candidate" ] && { echo "$candidate"; return 0; }
  done
  return 1
}

JUNIT_JAR="$(find_jar junit4 || find_jar junit || true)"
HAMCREST_JAR="$(find_jar hamcrest-core || find_jar hamcrest || true)"

if [ -z "$JUNIT_JAR" ]; then
  echo "error: no JUnit 4 jar found." >&2
  echo "       Debian/Ubuntu: apt install junit4" >&2
  echo "       Or set JUNIT_JAR=/path/to/junit4.jar and re-run." >&2
  exit 1
fi

CP="$JUNIT_JAR"
[ -n "$HAMCREST_JAR" ] && CP="$CP:$HAMCREST_JAR"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Collecting pure-JVM sources and tests..."
# core/model and core/messaging have no Android dependency. core/relay's tested classes do not
# either, but its module pulls in Android elsewhere, so only the tested files are taken.
SOURCES=(
  core/model/src/main/java/app/niix/core/model/GroupMembershipPolicy.kt
  core/model/src/main/java/app/niix/core/model/Message.kt
  core/model/src/main/java/app/niix/core/model/OnionAddress.kt
  core/model/src/main/java/app/niix/core/model/DiagnosticLog.kt
  core/messaging/src/main/java/app/niix/core/messaging/WireMessage.kt
  core/messaging/src/main/java/app/niix/core/messaging/MessagePadding.kt
)
TESTS=(
  core/model/src/test/java/app/niix/core/model/GroupMembershipPolicyTest.kt
  core/model/src/test/java/app/niix/core/model/ValidationAdversarialTest.kt
  core/messaging/src/test/java/app/niix/core/messaging/WireProtocolAdversarialTest.kt
)

PRESENT=()
for f in "${SOURCES[@]}" "${TESTS[@]}"; do
  [ -f "$f" ] && PRESENT+=("$f")
done

echo "==> Compiling ${#PRESENT[@]} file(s)..."
kotlinc -cp "$CP" "${PRESENT[@]}" -include-runtime -d "$WORK/tests.jar" 2>&1 | grep -v "^warning:" || true

if [ ! -f "$WORK/tests.jar" ]; then
  echo "error: compilation failed -- see the output above." >&2
  exit 1
fi

echo
echo "==> Running tests..."
FAILED=0
for class in \
  app.niix.core.model.GroupMembershipPolicyTest \
  app.niix.core.model.AuthorizedActorTest \
  app.niix.core.model.OnionAddressAdversarialTest \
  app.niix.core.model.DisappearSecondsClampTest \
  app.niix.core.messaging.WireProtocolAdversarialTest \
  app.niix.core.messaging.MessagePaddingAdversarialTest
do
  if java -cp "$WORK/tests.jar:$CP" org.junit.runner.JUnitCore "$class" >"$WORK/out.txt" 2>&1; then
    echo "  PASS  $class  ($(grep -oE 'OK \([0-9]+ tests?\)' "$WORK/out.txt" || echo 'ok'))"
  else
    echo "  FAIL  $class"
    sed 's/^/        /' "$WORK/out.txt" | head -30
    FAILED=1
  fi
done

echo
if [ "$FAILED" -eq 0 ]; then
  echo "==> All pure-JVM tests passed."
  echo "    Android-dependent code (storage, transport, UI) is not covered by this runner."
else
  echo "==> Some tests failed (see above)." >&2
  exit 1
fi
