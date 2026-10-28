#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x '/Applications/PyCharm.app/Contents/jbr/Contents/Home/bin/javac' ]; then
        export JAVA_HOME='/Applications/PyCharm.app/Contents/jbr/Contents/Home'
    else
        export JAVA_HOME="$(/usr/libexec/java_home)"
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$PWD/.tools/sdk}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/android-15}"
if [ ! -x "$BT/aapt2" ]; then BT="$SDK/build-tools/35.0.0"; fi
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
if [ ! -f "$ANDROID_JAR" ] || [ ! -x "$BT/aapt2" ]; then
    echo '请安装 Android SDK platform 35 / build-tools 35.0.0，或运行 scripts/bootstrap.sh。' >&2
    exit 1
fi
mkdir -p build/generated build/classes build/dex build/compiled-res
sed 's/<manifest /<manifest package="com.padpowerlab" /' app/src/main/AndroidManifest.xml > build/AndroidManifest.xml
"$BT/aapt2" compile --dir app/src/main/res -o build/compiled-res
find build/compiled-res -name '*.flat' -print0 | xargs -0 "$BT/aapt2" link -I "$ANDROID_JAR" \
    --manifest build/AndroidManifest.xml --java build/generated --debug-mode \
    --min-sdk-version 29 --target-sdk-version 35 --version-code 1 --version-name 1.0 \
    -o build/resources.apk
find app/src/main/java build/generated -name '*.java' > build/sources.list
javac -encoding UTF-8 --release 8 -Xlint:-options -classpath "$ANDROID_JAR" \
    -d build/classes @build/sources.list
(cd build/classes && zip -q -r ../classes.jar .)
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" --output build/dex build/classes.jar
cp build/resources.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
KEYSTORE="$PWD/.tools/powerlab-debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p .tools
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Pad Power Lab Debug,O=Local Development,C=CN'
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out build/PadPowerLab.apk build/aligned.apk
"$BT/apksigner" verify --verbose build/PadPowerLab.apk
echo "APK: $PWD/build/PadPowerLab.apk"
