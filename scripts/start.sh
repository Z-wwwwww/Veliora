#!/usr/bin/env bash
#
# Veliora 一键启动脚本 (macOS / Linux)
# 用法: scripts/start.sh          正常启动
#       scripts/start.sh --dev    使用 nodemon 热重载启动
#
set -euo pipefail

# 切换到项目根目录（脚本位于 scripts/ 子目录），保证在任意路径下执行都正确
cd "$(dirname "$0")/.."

# 颜色输出
info()  { printf '\033[0;36m[INFO]\033[0m %s\n' "$*"; }
ok()    { printf '\033[0;32m[ OK ]\033[0m %s\n' "$*"; }
warn()  { printf '\033[0;33m[WARN]\033[0m %s\n' "$*"; }
error() { printf '\033[0;31m[FAIL]\033[0m %s\n' "$*" >&2; }

# 1. 检查 Node.js
if ! command -v node >/dev/null 2>&1; then
  error "未检测到 Node.js，请先安装 (https://nodejs.org/)。"
  exit 1
fi
NODE_VER="$(node -v)"
ok "Node.js 版本: ${NODE_VER}"

# 2. 准备 .env 配置文件
if [ ! -f .env ]; then
  if [ -f .env.example ]; then
    cp .env.example .env
    warn "未找到 .env，已从 .env.example 复制生成。"
    warn "请注意默认密码为 111111，正式使用请修改 .env 中的 PASSWORD。"
  else
    warn "未找到 .env 与 .env.example，将使用代码内置默认配置。"
  fi
else
  ok "已找到 .env 配置文件。"
fi

# 3. 安装依赖（node_modules 不存在或 package.json 有更新时）
if [ ! -d node_modules ] || [ package.json -nt node_modules ]; then
  info "正在安装依赖，请稍候..."
  if [ -f package-lock.json ]; then
    npm ci || npm install
  else
    npm install
  fi
  ok "依赖安装完成。"
else
  ok "依赖已就绪，跳过安装。"
fi

# 4. 读取端口用于友好提示（默认 8080）
PORT="$(grep -E '^PORT=' .env 2>/dev/null | head -n1 | cut -d= -f2 | tr -d '[:space:]')"
PORT="${PORT:-8080}"

# 5. 启动服务
if [ "${1:-}" = "--dev" ]; then
  info "以开发模式 (nodemon 热重载) 启动..."
  info "访问地址: http://localhost:${PORT}"
  exec npm run dev
else
  info "启动 Veliora..."
  info "访问地址: http://localhost:${PORT}"
  exec npm start
fi
