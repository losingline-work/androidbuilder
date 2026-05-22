#!/usr/bin/env bash
set -euo pipefail

REPO_BASE="${TERMUX_REPO_BASE:-https://packages.termux.dev/apt/termux-main}"
ARCH="${TERMUX_ARCH:-aarch64}"
GRADLE_VERSION="${GRADLE_VERSION:-8.9}"
ANDROID_API="${ANDROID_API:-34}"
ANDROID_HOME_DEFAULT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME_DEFAULT/platforms/android-$ANDROID_API/android.jar}"
OUT_ZIP="${1:-app/src/main/assets/runtime/bootstrap-aarch64.zip}"
WORK_DIR="${RUNTIME_BOOTSTRAP_WORK_DIR:-.runtime-bootstrap}"

ROOT_PACKAGES=(
  bash
  coreutils
  curl
  findutils
  grep
  sed
  tar
  unzip
  zip
  zstd
  termux-tools
  openjdk-21
  aapt2
  d8
  apksigner
  android-tools
)

packages_file="$WORK_DIR/cache/Packages"
debs_dir="$WORK_DIR/debs"
extract_dir="$WORK_DIR/extract"
rootfs_dir="$WORK_DIR/rootfs"

mkdir -p "$WORK_DIR/cache" "$debs_dir" "$extract_dir" "$rootfs_dir/usr"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "Missing android.jar: $ANDROID_JAR" >&2
  echo "Install Android SDK platform android-$ANDROID_API or pass ANDROID_JAR=/path/to/android.jar" >&2
  exit 1
fi

echo "Fetching Termux package index..."
curl -L --fail --compressed \
  "$REPO_BASE/dists/stable/main/binary-$ARCH/Packages" \
  -o "$packages_file"

package_block() {
  awk -v pkg="$1" 'BEGIN{RS=""; FS="\n"} {
    for (i = 1; i <= NF; i++) {
      if ($i == "Package: " pkg) {
        print
        exit
      }
    }
  }' "$packages_file"
}

package_field() {
  package_block "$1" | awk -v key="$2" -F': ' '$1 == key {print substr($0, length(key) + 3); exit}'
}

normalize_dep() {
  sed 's/|.*$//; s/(.*)//; s/:any//; s/^[[:space:]]*//; s/[[:space:]]*$//'
}

queue="$WORK_DIR/queue.txt"
resolved="$WORK_DIR/resolved.txt"
filenames="$WORK_DIR/filenames.txt"
: > "$queue"
: > "$resolved"
: > "$filenames"
printf '%s\n' "${ROOT_PACKAGES[@]}" >> "$queue"

line_number=1
while [ "$line_number" -le "$(wc -l < "$queue" | tr -d ' ')" ]; do
  pkg="$(sed -n "${line_number}p" "$queue")"
  line_number=$((line_number + 1))
  [ -n "$pkg" ] || continue
  if grep -qx "$pkg" "$resolved"; then
    continue
  fi
  block="$(package_block "$pkg")"
  if [ -z "$block" ]; then
    echo "Package not found in Termux index: $pkg" >&2
    exit 1
  fi
  echo "$pkg" >> "$resolved"
  filename="$(printf '%s\n' "$block" | awk -F': ' '$1 == "Filename" {print $2; exit}')"
  if [ -n "$filename" ]; then
    echo "$filename" >> "$filenames"
  fi
  deps="$(printf '%s\n' "$block" | awk -F': ' '$1 == "Depends" {print substr($0, 10); exit}')"
  if [ -n "$deps" ]; then
    printf '%s\n' "$deps" |
      tr ',' '\n' |
      normalize_dep |
      sed '/^$/d' >> "$queue"
  fi
done

sort -u "$filenames" -o "$filenames"

echo "Downloading Termux packages..."
while IFS= read -r filename; do
  [ -n "$filename" ] || continue
  deb="$debs_dir/$(basename "$filename")"
  if [ ! -f "$deb" ]; then
    curl -L --fail "$REPO_BASE/$filename" -o "$deb"
  fi
done < "$filenames"

echo "Extracting packages..."
rm -rf "$extract_dir" "$rootfs_dir"
mkdir -p "$extract_dir" "$rootfs_dir/usr"
for deb in "$debs_dir"/*.deb; do
  deb_extract="$extract_dir/$(basename "$deb" .deb)"
  mkdir -p "$deb_extract/ar" "$deb_extract/data"
  tar -C "$deb_extract/ar" -xf "$deb"
  data_archive="$(find "$deb_extract/ar" -maxdepth 1 -name 'data.tar.*' -print -quit)"
  tar -C "$deb_extract/data" -xf "$data_archive"
  if [ -d "$deb_extract/data/data/data/com.termux/files/usr" ]; then
    tar -C "$deb_extract/data/data/data/com.termux/files" -cf - usr | tar -C "$rootfs_dir" -xf -
  elif [ -d "$deb_extract/data/data/com.termux/files/usr" ]; then
    tar -C "$deb_extract/data/data/com.termux/files" -cf - usr | tar -C "$rootfs_dir" -xf -
  else
    tar -C "$deb_extract/data" -cf - . | tar -C "$rootfs_dir" -xf -
  fi
done

echo "Installing Gradle $GRADLE_VERSION..."
gradle_zip="$WORK_DIR/cache/gradle-$GRADLE_VERSION-bin.zip"
curl -L --fail "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$gradle_zip"
mkdir -p "$rootfs_dir/usr/opt"
unzip -q -o "$gradle_zip" -d "$rootfs_dir/usr/opt"

cat > "$rootfs_dir/usr/bin/java" <<'EOF'
#!/system/bin/sh
PREFIX="${PREFIX:-$(cd "$(dirname "$0")/.." && pwd)}"
JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
export JAVA_HOME
export LD_LIBRARY_PATH="$JAVA_HOME/lib/server:$JAVA_HOME/lib:$PREFIX/lib:${LD_LIBRARY_PATH:-}"
exec "$JAVA_HOME/bin/java" "$@"
EOF
chmod +x "$rootfs_dir/usr/bin/java"

cat > "$rootfs_dir/usr/bin/javac" <<'EOF'
#!/system/bin/sh
PREFIX="${PREFIX:-$(cd "$(dirname "$0")/.." && pwd)}"
JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
export JAVA_HOME
export LD_LIBRARY_PATH="$JAVA_HOME/lib/server:$JAVA_HOME/lib:$PREFIX/lib:${LD_LIBRARY_PATH:-}"
exec "$JAVA_HOME/bin/javac" "$@"
EOF
chmod +x "$rootfs_dir/usr/bin/javac"

cat > "$rootfs_dir/usr/bin/d8" <<'EOF'
#!/system/bin/sh
PREFIX="${PREFIX:-$(cd "$(dirname "$0")/.." && pwd)}"
exec "$PREFIX/bin/java" -cp "$PREFIX/share/java/d8.jar" com.android.tools.r8.D8 "$@"
EOF
chmod +x "$rootfs_dir/usr/bin/d8"

cat > "$rootfs_dir/usr/bin/apksigner" <<'EOF'
#!/system/bin/sh
PREFIX="${PREFIX:-$(cd "$(dirname "$0")/.." && pwd)}"
exec "$PREFIX/bin/java" -jar "$PREFIX/share/java/apksigner.jar" "$@"
EOF
chmod +x "$rootfs_dir/usr/bin/apksigner"

cat > "$rootfs_dir/usr/bin/gradle" <<EOF
#!/system/bin/sh
PREFIX="\${PREFIX:-\$(cd "\$(dirname "\$0")/.." && pwd)}"
exec "\$PREFIX/bin/java" -classpath "\$PREFIX/opt/gradle-$GRADLE_VERSION/lib/gradle-launcher-$GRADLE_VERSION.jar" org.gradle.launcher.GradleMain "\$@"
EOF
chmod +x "$rootfs_dir/usr/bin/gradle"

echo "Installing Android SDK platform android-$ANDROID_API..."
mkdir -p "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API"
cp "$ANDROID_JAR" "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/android.jar"
cat > "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/source.properties" <<EOF
Pkg.Desc=Android SDK Platform $ANDROID_API
Pkg.UserSrc=false
Platform.Version=$ANDROID_API
AndroidVersion.ApiLevel=$ANDROID_API
Pkg.Revision=1
EOF

mkdir -p "$rootfs_dir/usr/android-sdk/build-tools/35.0.0"
for tool in aapt2 d8 apksigner; do
  cat > "$rootfs_dir/usr/android-sdk/build-tools/35.0.0/$tool" <<EOF
#!/system/bin/sh
PREFIX="\${PREFIX:-\$(cd "\$(dirname "\$0")/../../.." && pwd)}"
exec "\$PREFIX/bin/$tool" "\$@"
EOF
  chmod +x "$rootfs_dir/usr/android-sdk/build-tools/35.0.0/$tool"
done

mkdir -p "$(dirname "$OUT_ZIP")"
echo "Creating $OUT_ZIP..."
rm -f "$OUT_ZIP"
(cd "$rootfs_dir" && zip -qr "$OLDPWD/$OUT_ZIP" usr)

echo "Done: $OUT_ZIP"
echo "Installed packages: $(wc -l < "$resolved" | tr -d ' ')"
