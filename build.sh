#!/bin/bash
set -e
SDK=/home/v/Android/Sdk
BT=$SDK/build-tools/36.0.0
ANDROID_JAR=$SDK/platforms/android-36/android.jar
ROOT=/home/v/.local/project/redmi_n8p/work/module
OUT=$ROOT/out
rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex"

# 1. compile API stubs
javac -Xlint:-options --release 8 -d "$OUT/stubs" \
  -cp "$ANDROID_JAR" \
  $(find "$ROOT/stubs" -name '*.java')
jar cf "$OUT/xposed-stubs.jar" -C "$OUT/stubs" .

# 2. compile module against android + stubs
javac -Xlint:-options --release 8 -d "$OUT/classes" \
  -cp "$ANDROID_JAR:$OUT/xposed-stubs.jar" \
  $(find "$ROOT/src" -name '*.java')

# 3. dex only the module classes
"$BT/d8" --release --min-api 30 --lib "$ANDROID_JAR" --lib "$OUT/xposed-stubs.jar" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# 4. package
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -A "$ROOT/assets"

cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex && cd "$ROOT"

# 5. sign
if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/debug.keystore" -alias mod \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=RecentsFix" >/dev/null 2>&1
fi
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$ROOT/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/recentsfix.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/recentsfix.apk"
echo "BUILD OK: $OUT/recentsfix.apk"
ls -l "$OUT/recentsfix.apk"
