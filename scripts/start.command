#!/usr/bin/env bash
#
# Veliora 一键启动（macOS 双击运行）
# 双击本文件即可在终端中启动服务；首次可能需在“系统设置 > 隐私与安全性”中允许运行。
#
exec "$(dirname "$0")/start.sh" "$@"
