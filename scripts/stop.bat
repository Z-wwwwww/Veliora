@echo off
REM Veliora 一键停止脚本 (Windows)
REM 用法: scripts\stop.bat
setlocal enabledelayedexpansion
cd /d "%~dp0.."

REM 1. 读取 .env 中的 PORT（默认 8080）
set "PORT=8080"
if exist ".env" (
  for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
    if /i "%%a"=="PORT" if not "%%b"=="" set "PORT=%%b"
  )
)

REM 2. 查找监听该端口的进程
set "PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":%PORT% .*LISTENING"') do set "PID=%%p"
if not defined PID (
  echo [ OK ] 端口 %PORT% 上没有运行中的服务，无需停止。
  goto :end
)

REM 3. 确认是 node 进程，避免误杀其他程序
set "IMAGE="
for /f "tokens=1" %%i in ('tasklist /fi "PID eq !PID!" /nh /fo table 2^>nul') do if not defined IMAGE set "IMAGE=%%i"
echo [INFO] 端口 %PORT% 被进程 !IMAGE! ^(PID: !PID!^) 占用。
echo !IMAGE! | findstr /i "node" >nul
if errorlevel 1 (
  echo [WARN] 该进程不是 node，可能不是 Veliora，已跳过停止。
  echo [WARN] 如确认需要强制结束，请手动执行: taskkill /f /pid !PID!
  goto :fail
)

REM 4. 结束进程（/t 连同 nodemon 的子进程一并结束）
taskkill /f /t /pid !PID! >nul 2>nul
if errorlevel 1 (
  echo [FAIL] 停止失败，请尝试以管理员身份运行本脚本。
  goto :fail
)

REM 5. 复查端口已释放
set "PORT_IN_USE="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":%PORT% .*LISTENING"') do set "PORT_IN_USE=%%p"
if defined PORT_IN_USE (
  echo [WARN] 仍有进程 ^(PID: !PORT_IN_USE!^) 占用端口 %PORT%，请重新运行本脚本。
  goto :fail
)
echo [ OK ] Veliora 已停止，端口 %PORT% 已释放。

:end
pause
exit /b 0

:fail
echo.
pause
exit /b 1
