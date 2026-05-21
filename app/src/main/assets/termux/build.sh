#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_ID="${1:?project id required}"
JOB_ID="${2:?job id required}"
CALLBACK="${3:?callback url required}"
TOKEN="${4:?token required}"

ROOT="$HOME/androidbuilder/work/$PROJECT_ID/$JOB_ID"
ZIP="$ROOT/project.zip"
SRC="$ROOT/source"
LOG="$ROOT/build.log"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

mkdir -p "$SRC"
rm -rf "$SRC" "$ZIP" "$LOG"
mkdir -p "$SRC"

post_log() {
  printf "%s\n" "$1" | tee -a "$LOG"
  curl -fsS -X POST "$CALLBACK/projects/$PROJECT_ID/jobs/$JOB_ID/logs" -H "X-Build-Token: $TOKEN" --data-binary @"$LOG" >/dev/null || true
}

post_log "Downloading generated Android project..."
curl -fsS "$CALLBACK/projects/$PROJECT_ID/jobs/$JOB_ID/project.zip" -H "X-Build-Token: $TOKEN" -o "$ZIP"
unzip -q "$ZIP" -d "$SRC"

cd "$SRC"
if command -v aapt2 >/dev/null 2>&1; then
  if ! grep -q "android.aapt2FromMavenOverride" gradle.properties 2>/dev/null; then
    printf "\nandroid.aapt2FromMavenOverride=%s\n" "$(command -v aapt2)" >> gradle.properties
  fi
fi
post_log "Starting Gradle assembleDebug..."
if [ -x "./gradlew" ]; then
  ./gradlew assembleDebug >>"$LOG" 2>&1 || BUILD_FAILED=1
else
  gradle assembleDebug >>"$LOG" 2>&1 || BUILD_FAILED=1
fi

curl -fsS -X POST "$CALLBACK/projects/$PROJECT_ID/jobs/$JOB_ID/logs" -H "X-Build-Token: $TOKEN" --data-binary @"$LOG" >/dev/null || true

APK="$(find "$SRC" -path '*/build/outputs/apk/debug/*.apk' -type f | head -n 1 || true)"
if [ "${BUILD_FAILED:-0}" = "1" ] || [ -z "$APK" ]; then
  curl -fsS -X POST "$CALLBACK/projects/$PROJECT_ID/jobs/$JOB_ID/result" -H "X-Build-Token: $TOKEN" --data-binary "failed: build did not produce an APK" >/dev/null || true
  exit 1
fi

curl -fsS -X POST "$CALLBACK/projects/$PROJECT_ID/jobs/$JOB_ID/artifact" -H "X-Build-Token: $TOKEN" --data-binary @"$APK"
curl -fsS -X POST "$CALLBACK/projects/$PROJECT_ID/jobs/$JOB_ID/result" -H "X-Build-Token: $TOKEN" --data-binary "success: APK built"
