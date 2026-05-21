#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

pkg update -y
pkg install -y openjdk-17 gradle unzip curl aapt2
mkdir -p "$HOME/androidbuilder"
cp "$0" "$HOME/androidbuilder/setup-termux.sh" 2>/dev/null || true

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE="$ANDROID_HOME/cmdline-tools/latest"
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [ ! -x "$CMDLINE/bin/sdkmanager" ]; then
  TMP="$HOME/androidbuilder/cmdline-tools.zip"
  curl -L --fail "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o "$TMP"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
  unzip -q "$TMP" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$CMDLINE"
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$CMDLINE/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platforms;android-34" "platform-tools"

cat > "$HOME/androidbuilder/README.txt" <<'EOF'
Android Builder Termux setup

1. Copy build.sh from the Android Builder app assets to:
   $HOME/androidbuilder/build.sh
2. chmod +x $HOME/androidbuilder/build.sh
3. Enable Termux external commands:
   mkdir -p ~/.termux
   echo allow-external-apps=true >> ~/.termux/termux.properties
   Restart Termux.
EOF
