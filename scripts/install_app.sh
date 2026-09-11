#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$PWD/tools/platform-tools/adb}"
if [ ! -x "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; else
    echo "找不到 adb：请安装 Android Platform Tools，或设置 ADB=/path/to/adb" >&2; exit 1
  fi
fi
PKG=com.pekinlcc.reader

[ -f dist/Shiye.apk ] || { echo "dist/Shiye.apk 不存在，请先运行 bash scripts/build_app.sh" >&2; exit 1; }
COUNT="$("$ADB" devices | awk '$2=="device"{n++} END{print n+0}')"
if [ "$COUNT" -eq 0 ]; then
  echo "没有检测到已授权的设备。请用数据线连接平板，并在平板上允许 USB 调试。" >&2
  "$ADB" devices >&2
  exit 1
fi
if [ "$COUNT" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "检测到 $COUNT 台设备，adb 无法判断装到哪一台。请只连接平板，或设置 ANDROID_SERIAL=<序列号> 后重试。" >&2
  "$ADB" devices >&2
  exit 1
fi

if ! "$ADB" install -r dist/Shiye.apk; then
  cat >&2 <<'WARN'
安装失败。如果错误是 INSTALL_FAILED_UPDATE_INCOMPATIBLE，说明本机的
tools/reader.keystore 与平板上已安装的「拾页」不是同一个签名密钥。
找回原 keystore 后重新构建，或先卸载（会删除书库和阅读进度）：
    tools/platform-tools/adb uninstall com.pekinlcc.reader
WARN
  exit 1
fi

"$ADB" shell mkdir -p "/sdcard/Android/data/$PKG/files/library"
"$ADB" push --sync library/. "/sdcard/Android/data/$PKG/files/library/"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity"
echo "已安装并启动拾页。"
