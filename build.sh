#!/usr/bin/env bash
# Builds Wi-Fi Guardian without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Requirements (paths can be overridden with environment variables):
#   JDK 17                 JAVA_HOME
#   Android build-tools    BUILD_TOOLS   (e.g. .../sdk/build-tools/36.0.0)
#   Android platform jar   ANDROID_JAR   (e.g. .../sdk/platforms/android-36/android.jar)
#
# Signing: put your keystore data in keystore.properties (never commit it):
#   storeFile=release.jks
#   storePassword=...
#   keyAlias=...
#   keyPassword=...
# Without keystore.properties a throwaway debug key is generated.
set -euo pipefail
cd "$(dirname "$0")"

T="${ANDROID_BUILD_ROOT:-$USERPROFILE/AndroidBuild}"
JAVA_HOME="${JAVA_HOME:-$(ls -d "$T"/jdk-17* 2>/dev/null | head -1)}"
BUILD_TOOLS="${BUILD_TOOLS:-$T/sdk/build-tools/36.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$T/sdk/platforms/android-36/android.jar}"
export PATH="$JAVA_HOME/bin:$PATH"
exe() { if [ -f "$BUILD_TOOLS/$1.exe" ]; then echo "$BUILD_TOOLS/$1.exe"; elif [ -f "$BUILD_TOOLS/$1.bat" ]; then echo "$BUILD_TOOLS/$1.bat"; else echo "$BUILD_TOOLS/$1"; fi; }

OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" dist

"$(exe aapt2)" compile --dir app/res -o "$OUT/res.zip"
"$(exe aapt2)" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest app/AndroidManifest.xml \
    --min-sdk-version 34 --target-sdk-version 36 --java "$OUT/gen" "$OUT/res.zip"

javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -classpath "$BUILD_TOOLS/core-lambda-stubs.jar" -Xlint:-options -d "$OUT/classes" \
    $(find "$OUT/gen" app/src -name '*.java')

"$(exe d8)" --release --min-api 34 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && jar -uf ../unsigned.apk classes.dex)
"$(exe zipalign)" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/AndroidManifest.xml)
APK="dist/WifiGuardian-$VERSION.apk"
if [ -f keystore.properties ]; then
    prop() { sed -n "s/^$1=//p" keystore.properties | tr -d '\r'; }
    "$(exe apksigner)" sign --ks "$(prop storeFile)" --ks-pass "pass:$(prop storePassword)" \
        --ks-key-alias "$(prop keyAlias)" --key-pass "pass:$(prop keyPassword)" --out "$APK" "$OUT/aligned.apk"
else
    [ -f debug.jks ] || keytool -genkeypair -keystore debug.jks -storepass android -keypass android -alias debug \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Debug" >/dev/null 2>&1
    "$(exe apksigner)" sign --ks debug.jks --ks-pass pass:android --key-pass pass:android --out "$APK" "$OUT/aligned.apk"
fi
"$(exe apksigner)" verify "$APK"
echo "OK -> $APK"
