@echo off
REM Veliora 一键启动脚本 (Windows)
REM 用法: scripts\start.bat          正常启动
REM       scripts\start.bat --dev    使用 nodemon 热重载启动
setlocal enabledelayedexpansion
cd /d "%~dp0.."

REM 1. 检查 Node.js
where node >nul 2>nul
if errorlevel 1 (
  echo [FAIL] 未检测到 Node.js，请先安装 ^(https://nodejs.org/^)。
  goto :fail
)
for /f "delims=" %%v in ('node -v') do echo [ OK ] Node.js 版本: %%v

REM 2. 准备 .env 配置文件
if not exist ".env" (
  if exist ".env.example" (
    copy /y ".env.example" ".env" >nul
    echo [WARN] 未找到 .env，已从 .env.example 复制生成。
    echo [WARN] 默认密码为 111111，正式使用请修改 .env 中的 PASSWORD。
  ) else (
    echo [WARN] 未找到 .env 与 .env.example，将使用代码内置默认配置。
  )
) else (
  echo [ OK ] 已找到 .env 配置文件。
)

REM 3. 安装依赖
if not exist "node_modules" (
  echo [INFO] 正在安装依赖，请稍候...
  if exist "package-lock.json" (
    call npm ci || call npm install
  ) else (
    call npm install
  )
  if errorlevel 1 (
    echo [FAIL] 依赖安装失败，请查看上方 npm 错误信息。
    goto :fail
  )
  echo [ OK ] 依赖安装完成。
) else (
  echo [ OK ] 依赖已就绪，跳过安装。
)

REM 4. 检查端口占用
set "PORT_IN_USE="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":8080 .*LISTENING"') do set "PORT_IN_USE=%%p"
if defined PORT_IN_USE (
  echo [FAIL] 端口 8080 已被占用 ^(进程 PID: !PORT_IN_USE!^)。
  echo [FAIL] 请先关闭占用进程，或在 .env 中修改 PORT 后重试。
  goto :fail
)

REM 5. 启动服务
if "%~1"=="--dev" (
  echo [INFO] 以开发模式 ^(nodemon 热重载^) 启动...
  call npm run dev
) else (
  echo [INFO] 启动 Veliora...
  call npm start
)

echo.
echo [INFO] 服务已退出。如果是异常退出，错误信息见上方。
pause
exit /b 0

:fail
echo.
pause
exit /b 1
