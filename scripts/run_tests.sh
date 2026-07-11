#!/usr/bin/env bash
set -euo pipefail

# Runs the JVM unit tests. The Android Gradle Plugin needs JDK 17-21; a system
# default of JDK 22+ makes Gradle fail. This picks a compatible JDK automatically.

usage() {
  cat <<'EOF'
Usage: ./scripts/run_tests.sh [gradle-args...]

Runs :app:testDebugUnitTest with an auto-detected JDK 17-21. Extra args pass
through to Gradle, e.g.:
  ./scripts/run_tests.sh --tests '*TintaProtocolTest'
EOF
}

case "${1:-}" in -h|--help) usage; exit 0 ;; esac

# Echo the major version of $1 if it is a usable JDK in the 17..21 range.
jdk_major() {
  local jhome="$1" v
  [[ -x "$jhome/bin/java" ]] || return 1
  v="$("$jhome/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  [[ "$v" =~ ^[0-9]+$ ]] || return 1
  (( v >= 17 && v <= 21 )) && echo "$v"
}

pick_jdk() {
  # 1) an already-compatible JAVA_HOME
  if [[ -n "${JAVA_HOME:-}" ]] && jdk_major "$JAVA_HOME" >/dev/null; then
    echo "$JAVA_HOME"; return 0
  fi
  # 2) Android Studio's bundled runtime
  local c
  for c in "$HOME/android-studio/jbr" "/snap/android-studio/current/jbr"; do
    jdk_major "$c" >/dev/null 2>&1 && { echo "$c"; return 0; }
  done
  # 3) highest system JDK in 17..21
  local best="" bestv=0 v
  for c in /usr/lib/jvm/*; do
    v="$(jdk_major "$c" 2>/dev/null || true)"
    [[ -n "$v" ]] && (( v > bestv )) && { best="$c"; bestv="$v"; }
  done
  [[ -n "$best" ]] && { echo "$best"; return 0; }
  return 1
}

JDK="$(pick_jdk || true)"
if [[ -z "$JDK" ]]; then
  echo "No JDK 17-21 found. Set JAVA_HOME to one, or install e.g. openjdk-21." >&2
  exit 1
fi
export JAVA_HOME="$JDK"
echo "Using JAVA_HOME=$JAVA_HOME"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"
exec ./gradlew testDebugUnitTest "$@"
