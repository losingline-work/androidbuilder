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
HOME="\${HOME:-\$PREFIX/../home}"
TMPDIR="\${TMPDIR:-\$HOME/tmp}"
mkdir -p "\$HOME" "\$TMPDIR"
export HOME TMPDIR
exec "\$PREFIX/bin/java" -Duser.home="\$HOME" -Djava.io.tmpdir="\$TMPDIR" -Djdk.lang.Process.launchMechanism=VFORK \${GRADLE_OPTS:-} -classpath "\$PREFIX/opt/gradle-$GRADLE_VERSION/lib/gradle-launcher-$GRADLE_VERSION.jar" org.gradle.launcher.GradleMain "\$@"
EOF
chmod +x "$rootfs_dir/usr/bin/gradle"

echo "Installing Android SDK platform android-$ANDROID_API..."
mkdir -p "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API"
cp "$ANDROID_JAR" "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/android.jar"
cp "$ANDROID_JAR" "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/core-for-system-modules.jar"
cat > "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/source.properties" <<EOF
Pkg.Desc=Android SDK Platform $ANDROID_API
Pkg.UserSrc=false
Platform.Version=$ANDROID_API
AndroidVersion.ApiLevel=$ANDROID_API
Pkg.Revision=1
EOF
case "$ANDROID_API" in
  34) android_release=14 ;;
  35) android_release=15 ;;
  36) android_release=16 ;;
  *) android_release="$ANDROID_API" ;;
esac
cat > "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/build.prop" <<EOF
ro.build.version.sdk=$ANDROID_API
ro.build.version.codename=REL
ro.build.version.release=$android_release
ro.build.version.release_or_codename=$android_release
ro.build.version.preview_sdk=0
ro.build.version.preview_sdk_fingerprint=REL
ro.build.version.all_codenames=REL
ro.product.cpu.abi=arm64-v8a
ro.product.cpu.abi2=
ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi
ro.product.cpu.abilist32=armeabi-v7a,armeabi
ro.product.cpu.abilist64=arm64-v8a
ro.product.brand=Android
ro.product.manufacturer=Android
ro.product.device=generic
ro.build.product=generic
EOF
cat > "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/package.xml" <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns11="http://schemas.android.com/sdk/android/repo/repository2/03">
  <localPackage path="platforms;android-$ANDROID_API" obsolete="false">
    <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns11:platformDetailsType">
      <api-level>$ANDROID_API</api-level>
      <codename></codename>
      <base-extension>true</base-extension>
      <layoutlib api="15"/>
    </type-details>
    <revision>
      <major>1</major>
    </revision>
    <display-name>Android SDK Platform $ANDROID_API</display-name>
  </localPackage>
</ns2:repository>
EOF

mkdir -p "$rootfs_dir/usr/android-sdk/licenses"
cat > "$rootfs_dir/usr/android-sdk/licenses/android-sdk-license" <<'EOF'
24333f8a63b6825ea9c5514f83c2829b004d1fee
8933bad161af4178b1185d1a37fbf41ea5269c55
d56f5187479451eabf01fb78af6dfcb131a6481e
EOF
cat > "$rootfs_dir/usr/android-sdk/licenses/android-sdk-preview-license" <<'EOF'
84831b9409646a918e30573bab4c9c91346d8abd
EOF

for build_tools_api in 35 "$ANDROID_API" 34; do
  build_tools_dir="$rootfs_dir/usr/android-sdk/build-tools/$build_tools_api.0.0"
  mkdir -p "$build_tools_dir"
  cat > "$build_tools_dir/source.properties" <<EOF
Pkg.Desc=Android SDK Build-Tools $build_tools_api
Pkg.Revision=$build_tools_api.0.0
EOF
  cat > "$build_tools_dir/package.xml" <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns5="http://schemas.android.com/repository/android/generic/02">
  <localPackage path="build-tools;$build_tools_api.0.0" obsolete="false">
    <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns5:genericDetailsType"/>
    <revision>
      <major>$build_tools_api</major>
      <minor>0</minor>
      <micro>0</micro>
    </revision>
    <display-name>Android SDK Build-Tools $build_tools_api</display-name>
  </localPackage>
</ns2:repository>
EOF
  for tool in aapt2 aapt aidl d8 dexdump zipalign apksigner split-select bcc_compat lld llvm-rs-cc; do
    if [ -x "$rootfs_dir/usr/bin/$tool" ]; then
      cat > "$build_tools_dir/$tool" <<EOF
#!/system/bin/sh
PREFIX="\${PREFIX:-\$(cd "\$(dirname "\$0")/../../.." && pwd)}"
exec "\$PREFIX/bin/$tool" "\$@"
EOF
    else
      cat > "$build_tools_dir/$tool" <<EOF
#!/system/bin/sh
echo "Android SDK tool $tool is not bundled in this runtime." >&2
exit 127
EOF
    fi
    chmod +x "$build_tools_dir/$tool"
  done
  printf '\120\113\005\006\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000' > "$build_tools_dir/core-lambda-stubs.jar"
done

for alias_api in 34; do
  [ "$alias_api" = "$ANDROID_API" ] && continue
  alias_dir="$rootfs_dir/usr/android-sdk/platforms/android-$alias_api"
  mkdir -p "$alias_dir"
  cp "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/android.jar" "$alias_dir/android.jar"
  cp "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/core-for-system-modules.jar" "$alias_dir/core-for-system-modules.jar"
  sed "s/ro.build.version.sdk=$ANDROID_API/ro.build.version.sdk=$alias_api/g; s/ro.build.version.release=$android_release/ro.build.version.release=14/g; s/ro.build.version.release_or_codename=$android_release/ro.build.version.release_or_codename=14/g" "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/build.prop" > "$alias_dir/build.prop"
  sed "s/android-$ANDROID_API/android-$alias_api/g; s/Platform $ANDROID_API/Platform $alias_api/g; s/<api-level>$ANDROID_API<\\//<api-level>$alias_api<\\//g" "$rootfs_dir/usr/android-sdk/platforms/android-$ANDROID_API/package.xml" > "$alias_dir/package.xml"
  cat > "$alias_dir/source.properties" <<EOF
Pkg.Desc=Android SDK Platform $alias_api
Pkg.UserSrc=false
Platform.Version=$alias_api
AndroidVersion.ApiLevel=$alias_api
Pkg.Revision=1
EOF
done

for build_tools_api in 35 "$ANDROID_API" 34; do
  build_tools_dir="$rootfs_dir/usr/android-sdk/build-tools/$build_tools_api.0.0"
  for tool in aapt2 aapt aidl d8 dexdump zipalign apksigner split-select bcc_compat lld llvm-rs-cc; do
    [ -e "$build_tools_dir/$tool" ] && continue
    cat > "$build_tools_dir/$tool" <<EOF
#!/system/bin/sh
echo "Android SDK tool $tool is not bundled in this runtime." >&2
exit 127
EOF
    chmod +x "$build_tools_dir/$tool"
  done
done

mkdir -p "$(dirname "$OUT_ZIP")"
echo "Creating $OUT_ZIP..."
rm -f "$OUT_ZIP"
(cd "$rootfs_dir" && zip -qr "$OLDPWD/$OUT_ZIP" usr)

echo "Done: $OUT_ZIP"
echo "Installed packages: $(wc -l < "$resolved" | tr -d ' ')"
