#!/bin/bash
# Runs inside the gradle:jdk17 container (see build.ps1). SDK and Gradle caches are Docker volumes,
# so only the first build downloads them.
set -euo pipefail
export ANDROID_HOME=/opt/android-sdk
SDKM=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager

if [ ! -x "$SDKM" ]; then
    echo "== Installing Android command-line tools"
    command -v unzip >/dev/null || (apt-get update -qq && apt-get install -y -qq unzip >/dev/null)
    cd /tmp
    curl -fsSL -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    rm -rf cmdline-tools && unzip -q cmdline.zip
    mkdir -p $ANDROID_HOME/cmdline-tools
    rm -rf $ANDROID_HOME/cmdline-tools/latest
    mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest
fi
if [ ! -d "$ANDROID_HOME/platforms/android-35" ] || [ ! -d "$ANDROID_HOME/build-tools/35.0.0" ]; then
    echo "== Installing SDK platform 35 + build-tools"
    yes | $SDKM --licenses >/dev/null || true
    $SDKM --install "platforms;android-35" "build-tools;35.0.0" "platform-tools" >/dev/null
fi

cd /project
if [ ! -f keystore/keystore.properties ]; then
    echo "== Creating release signing key (keystore/ - keep it for future updates)"
    mkdir -p keystore
    PW=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
    keytool -genkeypair -keystore keystore/release.jks -storetype PKCS12 -alias homedesk \
        -keyalg RSA -keysize 2048 -validity 36500 -storepass "$PW" -keypass "$PW" \
        -dname "CN=HomeDesk" >/dev/null 2>&1
    printf 'storeFile=release.jks\nkeyAlias=homedesk\npassword=%s\n' "$PW" > keystore/keystore.properties
fi

echo "== Building"
gradle --no-daemon -q assembleRelease
cp app/build/outputs/apk/release/app-release.apk /project/HomeDesk.apk
echo "== Done: HomeDesk.apk"
