#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Locate a JDK 17 instead of assuming one Homebrew prefix.
if [ -z "${JAVA_BIN:-}" ]; then
  for candidate in /opt/homebrew/opt/openjdk@17/bin /usr/local/opt/openjdk@17/bin "${JAVA_HOME:-/nonexistent}/bin"; do
    if [ -x "$candidate/javac" ]; then JAVA_BIN="$candidate"; break; fi
  done
fi
if [ -z "${JAVA_BIN:-}" ] && command -v javac >/dev/null 2>&1; then JAVA_BIN="$(dirname "$(command -v javac)")"; fi
if [ -z "${JAVA_BIN:-}" ]; then echo "找不到 JDK：请安装 openjdk@17，或设置 JAVA_BIN=/path/to/jdk/bin" >&2; exit 1; fi

# 签名口令：优先环境变量 KS_PASS，其次本地 tools/keystore.pass（该目录不入库）
if [ -z "${KS_PASS:-}" ] && [ -f tools/keystore.pass ]; then KS_PASS="$(cat tools/keystore.pass)"; fi
KS_PASS="${KS_PASS:?未设置签名口令：请设置 KS_PASS 环境变量，或把口令写入 tools/keystore.pass}"

SDK="$PWD/tools/android-sdk"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
for required in "$BT/aapt2" "$BT/zipalign" "$ANDROID_JAR" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar"; do
  [ -e "$required" ] || { echo "缺少构建工具：$required" >&2; exit 1; }
done

rm -rf build/classes build/dex
rm -f build/base.apk build/resources.zip build/classes.jar build/unsigned.apk build/aligned.apk build/signed.apk
mkdir -p build/classes build/dex dist

"$BT/aapt2" compile --dir app/res -o build/resources.zip
"$BT/aapt2" link -o build/base.apk --manifest app/AndroidManifest.xml -I "$ANDROID_JAR" -A app/assets build/resources.zip
# Keep the optional test source in an array: the project path contains a space.
TEST_SOURCES=()
if [ -n "${READER_TEST_SOURCE:-}" ]; then TEST_SOURCES=("$READER_TEST_SOURCE"); fi
"$JAVA_BIN/javac" -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" -d build/classes app/src/com/pekinlcc/reader/MainActivity.java ${TEST_SOURCES[@]+"${TEST_SOURCES[@]}"}
"$JAVA_BIN/jar" cf build/classes.jar -C build/classes .
"$JAVA_BIN/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f tools/reader.keystore ]; then
  cat >&2 <<'WARN'
================================================================================
警告：找不到 tools/reader.keystore，正在生成一个全新的签名密钥。

新密钥与设备上已安装的「拾页」签名不同，覆盖安装会失败：
    INSTALL_FAILED_UPDATE_INCOMPATIBLE

如果平板上已装旧版，请先找回原来的 keystore 再构建。
若确实要用新密钥，需要先卸载：
    adb uninstall com.pekinlcc.reader
卸载会同时删除应用专属目录里的书库和全部阅读进度，之后必须重新导入。

tools/ 在 .gitignore 中，keystore 不会随仓库保存，请自行备份。
================================================================================
WARN
  "$JAVA_BIN/keytool" -genkeypair -keystore tools/reader.keystore -storepass "$KS_PASS" -keypass "$KS_PASS" -alias reader -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Personal Reader'
fi

# Sign to a scratch path first so a failed run never leaves dist/ with a half-written APK.
"$JAVA_BIN/java" -jar "$BT/lib/apksigner.jar" sign --ks tools/reader.keystore --ks-pass "pass:$KS_PASS" --out build/signed.apk build/aligned.apk
"$JAVA_BIN/java" -jar "$BT/lib/apksigner.jar" verify build/signed.apk
mv -f build/signed.apk dist/Shiye.apk
[ -f build/signed.apk.idsig ] && mv -f build/signed.apk.idsig dist/Shiye.apk.idsig || true
echo "已生成 dist/Shiye.apk  ($(sed -n 's/.*android:versionName="\([^"]*\)".*/版本 \1/p' app/AndroidManifest.xml), $(wc -c < dist/Shiye.apk | tr -d " ") 字节)"
