#!/usr/bin/env bash
#
# Veliora 一键停止（macOS 双击运行）
# 双击本文件即可在终端中停止服务。
#
exec "$(dirname "$0")/stop.sh" "$@"
