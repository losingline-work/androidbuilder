Place an optional bootstrap-arm64.zip in this directory before building the APK.

Supported zip layouts:

1. Termux-style:
   usr/bin/gradle
   usr/bin/java
   usr/bin/aapt2
   usr/android-sdk/platforms/android-34/android.jar
   home/

2. Short form:
   bin/gradle
   bin/java
   bin/aapt2
   android-sdk/platforms/android-34/android.jar

At runtime the installer extracts entries into:
  files/runtime/usr
  files/runtime/home

If GPLv3 Termux code or binaries are bundled, distribute corresponding source
and preserve all required license notices.
