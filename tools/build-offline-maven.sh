#!/usr/bin/env bash
#
# Produce the offline Maven bundle that the on-device embedded Gradle build resolves from with NO network.
# Run this ONCE on a machine WITH network access + a JDK + `gradle` on PATH. The resulting zip is imported
# on the device via Settings → "Import offline-maven.zip" (or shipped as an app asset).
#
# The coordinate list below MUST stay in sync with
#   app/src/main/java/com/androidbuilder/agent/DependencyCatalog.java :: offlineBundleCoordinates()
# (capability libraries + base toolchain/UI). A drift means the model is told a lib is cached but it isn't.
#
# Output: app/src/main/assets/offline-maven.zip  (git-ignored; large binary, never committed)
#
# Note on the Android Gradle Plugin: the AGP/Gradle toolchain also warms into the runtime's own gradle
# cache from the FIRST successful online build, so even if the plugin marker is incomplete in this bundle,
# one online build primes it. The bundle's primary job is the APP libraries (charts, image, AndroidX, ...).

set -euo pipefail

OUT_ZIP="${1:-app/src/main/assets/offline-maven.zip}"
WORK="${OFFLINE_MAVEN_WORK_DIR:-.offline-maven-build}"
GUH="$WORK/gradle-user-home"
PROJ="$WORK/resolve"
REPO="$WORK/offline-maven"

# Keep in sync with DependencyCatalog.offlineBundleCoordinates().
COORDS=(
  "com.github.PhilJay:MPAndroidChart:v3.1.0"
  "com.squareup.picasso:picasso:2.8"
  "com.github.bumptech.glide:glide:4.16.0"
  "com.airbnb.android:lottie:6.4.0"
  "com.squareup.okhttp3:okhttp:3.12.13"
  "com.squareup.retrofit2:retrofit:2.11.0"
  "com.squareup.retrofit2:converter-gson:2.11.0"
  "io.reactivex.rxjava3:rxjava:3.1.9"
  "io.reactivex.rxjava3:rxandroid:3.0.2"
  "org.greenrobot:eventbus:3.3.1"
  "com.jakewharton.threetenabp:threetenabp:1.4.7"
  "com.google.zxing:core:3.5.3"
  "com.squareup.moshi:moshi:1.15.1"
  "androidx.appcompat:appcompat:1.7.0"
  "androidx.core:core:1.13.1"
  "androidx.recyclerview:recyclerview:1.3.2"
  "androidx.constraintlayout:constraintlayout:2.2.0"
  "com.google.android.material:material:1.12.0"
  "org.jetbrains.kotlin:kotlin-stdlib:1.8.22"
  "com.android.tools.build:gradle:8.7.3"
  "com.android.application:com.android.application.gradle.plugin:8.7.3"
)

rm -rf "$WORK"
mkdir -p "$PROJ" "$GUH" "$REPO"

# 1) A throwaway gradle project that depends on every coordinate.
cat > "$PROJ/settings.gradle" <<'EOF'
rootProject.name = 'offline-maven-resolve'
EOF
{
  echo "configurations { offlineAll }"
  echo "configurations.offlineAll.transitive = true"
  echo "repositories { google(); mavenCentral(); maven { url 'https://jitpack.io' } }"
  echo "dependencies {"
  for c in "${COORDS[@]}"; do echo "  offlineAll '$c'"; done
  echo "}"
  # lenient artifact view downloads every resolvable artifact (.aar/.jar) without failing on
  # metadata-only modules (e.g. the AGP plugin marker); .pom/.module land in the cache regardless.
  echo "tasks.register('resolveAll') { doLast { configurations.offlineAll.incoming.artifactView { lenient = true }.artifacts.each { it.file } } }"
} > "$PROJ/build.gradle"

# 2) Resolve into a PRIVATE gradle cache (so we transcode only what we asked for).
( cd "$PROJ" && gradle --no-daemon -g "$(cd "$GUH" && pwd)" resolveAll )

# 3) Transcode the modules-2 cache (<group>/<artifact>/<version>/<sha1>/<file>) into Maven repo layout
#    (<group-with-slashes>/<artifact>/<version>/<file>) — exactly what DependencyGuard.hasLocalArtifact()
#    and the on-device init-script repository expect.
FILES21="$GUH/caches/modules-2/files-2.1"
if [ ! -d "$FILES21" ]; then
  echo "ERROR: no resolved cache at $FILES21 — did resolution fail?" >&2
  exit 1
fi
count=0
while IFS= read -r -d '' f; do
  rel="${f#"$FILES21"/}"            # group/artifact/version/sha1/filename
  grp="${rel%%/*}";  rest="${rel#*/}"
  art="${rest%%/*}"; rest="${rest#*/}"
  ver="${rest%%/*}"; rest="${rest#*/}"
  file="${rest#*/}"                 # drop the sha1 directory
  dest="$REPO/${grp//.//}/$art/$ver"
  mkdir -p "$dest"
  cp "$f" "$dest/$file"
  count=$((count + 1))
done < <(find "$FILES21" -type f -print0)
echo "Transcoded $count artifact files into Maven layout."

# 4) Sanity check: the headline chart lib must be present as an .aar + .pom.
chart="$REPO/com/github/PhilJay/MPAndroidChart/v3.1.0"
if [ ! -f "$chart/MPAndroidChart-v3.1.0.aar" ] || [ ! -f "$chart/MPAndroidChart-v3.1.0.pom" ]; then
  echo "ERROR: MPAndroidChart aar/pom missing — bundle is incomplete." >&2
  exit 1
fi

# 5) Zip into the app assets.
mkdir -p "$(dirname "$OUT_ZIP")"
rm -f "$OUT_ZIP"
ABS_OUT="$(cd "$(dirname "$OUT_ZIP")" && pwd)/$(basename "$OUT_ZIP")"
( cd "$REPO" && zip -qr "$ABS_OUT" . )
echo "Wrote $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1))"
echo "Next: install it on the device via Settings → Import offline-maven.zip, then set dependency mode to 'local cache'."
