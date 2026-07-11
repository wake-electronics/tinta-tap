#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./scripts/build_and_sync_apk.sh [--sdk-dir DIR] [--sync-dir DIR] [--no-copy]

Builds debug APK via Gradle and copies it to a sync folder for phone sideload.

Options:
  --sdk-dir DIR     Android SDK path override (same as ANDROID_SDK_ROOT)
  --sync-dir DIR    Destination folder for copied APK (default: $HOME/Sync)
  --no-copy         Build only, do not copy
  -h, --help        Show this help

Environment:
  ANDROID_SDK_ROOT  Android SDK path (auto-detected as $HOME/Android/Sdk if present)
  JAVA_HOME         JDK path (auto-detected from android-studio or system)
EOF
}

SYNC_DIR="${HOME}/Sync"
DO_COPY=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sync-dir)
      [[ $# -ge 2 ]] || { echo "Missing value for --sync-dir" >&2; exit 2; }
      SYNC_DIR="$2"
      shift 2
      ;;
    --sdk-dir)
      [[ $# -ge 2 ]] || { echo "Missing value for --sdk-dir" >&2; exit 2; }
      export ANDROID_SDK_ROOT="$2"
      shift 2
      ;;
    --no-copy)
      DO_COPY=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
APK_REL="app/build/outputs/apk/debug/app-debug.apk"
APK_NAME="tinta-tap-debug.apk"

# Auto-detect Java
if [[ -z "${JAVA_HOME:-}" && -d "${HOME}/android-studio/jbr" ]]; then
  export JAVA_HOME="${HOME}/android-studio/jbr"
fi
if [[ -z "${JAVA_HOME:-}" && -d "/snap/android-studio/current/jbr" ]]; then
  export JAVA_HOME="/snap/android-studio/current/jbr"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

# Auto-detect Android SDK
if [[ -z "${ANDROID_SDK_ROOT:-}" && -d "${HOME}/Android/Sdk" ]]; then
  export ANDROID_SDK_ROOT="${HOME}/Android/Sdk"
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" || ! -d "${ANDROID_SDK_ROOT}" ]]; then
  cat >&2 <<'EOF'
Android SDK not found.

Set one of:
  1) ANDROID_SDK_ROOT=/path/to/Android/Sdk
  2) --sdk-dir /path/to/Android/Sdk
EOF
  exit 1
fi

# Write local.properties for Gradle
LOCAL_PROPERTIES="${PROJECT_DIR}/local.properties"
ESCAPED_SDK_DIR="$(printf '%s' "${ANDROID_SDK_ROOT}" | sed 's/\\/\\\\/g')"
printf 'sdk.dir=%s\n' "${ESCAPED_SDK_DIR}" > "${LOCAL_PROPERTIES}"

echo "Project: ${PROJECT_DIR}"
echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT}"
if [[ -n "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME=${JAVA_HOME}"
fi

(
  cd "${PROJECT_DIR}"
  ./gradlew :app:assembleDebug --no-daemon
)

APK_PATH="${PROJECT_DIR}/${APK_REL}"
if [[ ! -f "${APK_PATH}" ]]; then
  echo "APK not found after build: ${APK_PATH}" >&2
  exit 1
fi

echo "Built APK: ${APK_PATH}"

if [[ "${DO_COPY}" -eq 1 ]]; then
  mkdir -p "${SYNC_DIR}"
  DEST="${SYNC_DIR}/${APK_NAME}"
  cp "${APK_PATH}" "${DEST}"
  echo "Copied APK to: ${DEST}"
fi
