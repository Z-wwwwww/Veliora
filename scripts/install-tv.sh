#!/usr/bin/env bash
#
# Veliora APK 一键安装到电视 (通过 ADB 网络调试)
# 用法: scripts/install-tv.sh <电视IP>[:端口]
#
# 前置条件:
#   1. 电视与本机在同一局域网
#   2. 电视已开启「ADB 调试 / 网络调试」（一般在 设置 > 关于 > 连点版本号打开开发者选项）
#
set -euo pipefail
cd "$(dirname "$0")/.."

error() { printf '\033[0;31m[FAIL]\033[0m %s\n' "$*" >&2; }
info()  { printf '\033[0;36m[INFO]\033[0m %s\n' "$*"; }
ok()    { printf '\033[0;32m[ OK ]\033[0m %s\n' "$*"; }

if [ $# -lt 1 ]; then
  error "用法: scripts/install-tv.sh <电视IP>[:端口]   例如: scripts/install-tv.sh 192.168.1.50"
  exit 1
fi

TV_ADDR="$1"
case "$TV_ADDR" in *:*) ;; *) TV_ADDR="$TV_ADDR:5555" ;; esac

APK="android/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  error "未找到 APK，请先运行 scripts/build-apk.sh"
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  # 尝试 Android SDK 里的 adb
  for candidate in "$HOME/Library/Android/sdk/platform-tools/adb" "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"; do
    if [ -x "$candidate" ]; then alias adb="$candidate"; ADB="$candidate"; break; fi
  done
  ADB="${ADB:-}"
  if [ -z "$ADB" ]; then
    error "未找到 adb。安装: brew install --cask android-platform-tools"
    exit 1
  fi
else
  ADB="$(command -v adb)"
fi

info "连接电视 $TV_ADDR ..."
"$ADB" connect "$TV_ADDR"

info "安装 APK（覆盖旧版本）..."
"$ADB" -s "$TV_ADDR" install -r "$APK"

ok "安装完成！在电视桌面的应用列表中找到 Veliora 即可打开。"
