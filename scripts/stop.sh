#!/usr/bin/env bash
#
# Veliora 一键停止脚本 (macOS / Linux)
# 用法: scripts/stop.sh
#
set -euo pipefail

# 切换到项目根目录（脚本位于 scripts/ 子目录），保证在任意路径下执行都正确
cd "$(dirname "$0")/.."

# 颜色输出
info()  { printf '\033[0;36m[INFO]\033[0m %s\n' "$*"; }
ok()    { printf '\033[0;32m[ OK ]\033[0m %s\n' "$*"; }
warn()  { printf '\033[0;33m[WARN]\033[0m %s\n' "$*"; }
error() { printf '\033[0;31m[FAIL]\033[0m %s\n' "$*" >&2; }

# 1. 读取端口（默认 8080）
PORT="$(grep -E '^PORT=' .env 2>/dev/null | head -n1 | cut -d= -f2 | tr -d '[:space:]')"
PORT="${PORT:-8080}"

# 2. 查找监听该端口的进程（优先 lsof，缺失时退回 fuser）
PIDS=""
if command -v lsof >/dev/null 2>&1; then
  PIDS="$(lsof -ti "tcp:${PORT}" -sTCP:LISTEN 2>/dev/null || true)"
elif command -v fuser >/dev/null 2>&1; then
  PIDS="$(fuser "${PORT}/tcp" 2>/dev/null | tr -s ' ' '\n' || true)"
else
  error "未找到 lsof 或 fuser，无法查找端口占用进程。"
  exit 1
fi

if [ -z "${PIDS}" ]; then
  ok "端口 ${PORT} 上没有运行中的服务，无需停止。"
  exit 0
fi

# 3. 逐个确认是 node 进程后结束，避免误杀其他程序
STOPPED=0
for PID in ${PIDS}; do
  COMM="$(ps -p "${PID}" -o comm= 2>/dev/null | tr -d '[:space:]')"
  case "${COMM}" in
    *node*|*nodemon*)
      info "停止进程 ${COMM} (PID: ${PID}) ..."
      kill "${PID}" 2>/dev/null || true
      # 最多等待 5 秒优雅退出，超时后强制结束
      for _ in 1 2 3 4 5; do
        kill -0 "${PID}" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "${PID}" 2>/dev/null; then
        warn "进程未响应，强制结束..."
        kill -9 "${PID}" 2>/dev/null || true
      fi
      STOPPED=1
      ;;
    *)
      warn "端口 ${PORT} 被 ${COMM:-未知进程} (PID: ${PID}) 占用，不是 node，已跳过。"
      warn "如确认需要强制结束，请手动执行: kill ${PID}"
      ;;
  esac
done

if [ "${STOPPED}" = "1" ]; then
  ok "Veliora 已停止，端口 ${PORT} 已释放。"
else
  exit 1
fi
