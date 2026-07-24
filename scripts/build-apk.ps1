# Veliora Android TV APK 一键构建脚本 (Windows)
# 用法: powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1 [-Clean]
#
# 流程: 同步网页资源 → 中和密码门补丁 → (首次)生成自签名 keystore → gradle 构建
# 与 scripts/build-apk.sh (macOS/Linux) 保持一致
param([switch]$Clean)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$AndroidDir = Join-Path $Root 'android'
$AssetsDir = Join-Path $AndroidDir 'app\src\main\assets'

function Info($msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[ OK ] $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------
# 0. 构建环境检查: JDK 17/21 + Android SDK + Gradle
#    (AGP 8.7 需 JDK 17+; Gradle 8.10 最高支持 JDK 23, 本机 JDK 25 不可用)
# ---------------------------------------------------------------
$jdkCandidates = @(
    'C:\Program Files\Java\jdk-17',
    'C:\Program Files\Java\jdk-21',
    'C:\Program Files\Eclipse Adoptium\jdk-17*',
    'C:\Program Files\Eclipse Adoptium\jdk-21*',
    'C:\Program Files\Amazon Corretto\jdk17*',
    'C:\Program Files\Amazon Corretto\jdk21*'
)
$jdk = $null
foreach ($c in $jdkCandidates) {
    $hit = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit -and (Test-Path (Join-Path $hit.FullName 'bin\java.exe'))) { $jdk = $hit.FullName; break }
}
if (-not $jdk) { Fail '未找到 JDK 17/21 (AGP 需要且 Gradle 8.10 不支持 JDK 24+)' }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Ok "JDK: $jdk"

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms'))) {
    Fail "未找到 Android SDK: $env:ANDROID_HOME (需 platforms;android-35 与 build-tools;35.0.0)"
}
Ok "Android SDK: $env:ANDROID_HOME"
"sdk.dir=$($env:ANDROID_HOME -replace '\\','/')" | Out-File (Join-Path $AndroidDir 'local.properties') -Encoding ascii

$gradle = $null
$gradlewBat = Join-Path $AndroidDir 'gradlew.bat'
if (Test-Path $gradlewBat) { $gradle = $gradlewBat }
elseif (Get-Command gradle -ErrorAction SilentlyContinue) { $gradle = 'gradle' }
else {
    $localGradle = Join-Path $env:LOCALAPPDATA 'Gradle\gradle-8.10.2\bin\gradle.bat'
    if (Test-Path $localGradle) { $gradle = $localGradle }
}
if (-not $gradle) { Fail '未找到 gradle (期望 android\gradlew.bat 或 %LOCALAPPDATA%\Gradle\gradle-8.10.2)' }
Ok "Gradle: $gradle"

# ---------------------------------------------------------------
# 1. 同步网页资源到 assets/ (assets 根目录 = Web 根目录)
# ---------------------------------------------------------------
Info '同步网页资源...'
if (Test-Path $AssetsDir) { Remove-Item -Recurse -Force $AssetsDir }
New-Item -ItemType Directory -Force $AssetsDir | Out-Null
Copy-Item (Join-Path $Root 'index.html'), (Join-Path $Root 'player.html') $AssetsDir
foreach ($d in 'css', 'js', 'libs', 'image') {
    Copy-Item -Recurse (Join-Path $Root $d) (Join-Path $AssetsDir $d)
}
Ok '已同步 index.html player.html css/ js/ libs/ image/'

# ---------------------------------------------------------------
# 2. 密码门中和补丁 (只改 assets 拷贝件, 不动源码)
#    与 build-apk.sh 一致: {{PASSWORD}} → 内部密码 SHA-256 + 预写验证状态
# ---------------------------------------------------------------
Info '打密码门中和补丁...'
$internalPassword = 'veliora-apk-internal'
$sha256 = [System.Security.Cryptography.SHA256]::Create()
$hash = ($sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($internalPassword)) |
    ForEach-Object { $_.ToString('x2') }) -join ''
$seedScript = "try{localStorage.setItem('passwordVerified',JSON.stringify({verified:true,timestamp:Date.now(),passwordHash:'$hash'}));}catch(e){}"

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
foreach ($page in 'index.html', 'player.html') {
    $f = Join-Path $AssetsDir $page
    $content = [IO.File]::ReadAllText($f)
    if ($content -notmatch '\{\{PASSWORD\}\}') { Fail "$page 中未找到 {{PASSWORD}} 占位符, 上游模板可能已变化" }
    $content = $content.Replace('"{{PASSWORD}}";', "`"$hash`"; $seedScript")
    [IO.File]::WriteAllText($f, $content, $utf8NoBom)
}
Ok "密码门已中和 (内部哈希: $($hash.Substring(0,12))...)"

# ---------------------------------------------------------------
# 3. 首次构建自动生成自签名 keystore (gitignore, 不入库)
# ---------------------------------------------------------------
$keystore = Join-Path $AndroidDir 'keystore\veliora.jks'
if (-not (Test-Path $keystore)) {
    Info '生成自签名 keystore (仅本机使用)...'
    New-Item -ItemType Directory -Force (Split-Path $keystore) | Out-Null
    # 经 cmd 调用: keytool 的进度信息走 stderr, 直接调用会被 PS5.1 包装成错误
    & cmd /c "`"$jdk\bin\keytool.exe`" -genkeypair -keystore `"$keystore`" -storepass veliora -keypass veliora -alias veliora -keyalg RSA -keysize 2048 -validity 36500 -dname `"CN=Veliora, OU=Local, O=Veliora, C=CN`" >nul 2>&1"
    if (-not (Test-Path $keystore)) { Fail 'keystore 生成失败' }
    Ok "keystore 已生成: $keystore"
}

# ---------------------------------------------------------------
# 4. Gradle 构建
# ---------------------------------------------------------------
Set-Location $AndroidDir
# 命令行 -D 优先级高于用户级 ~\.gradle\gradle.properties
# (其 org.gradle.java.home 可能指向旧 JDK, jvmargs 可能含 JDK17 已移除的 MaxPermSize)
$gradleArgs = @(
    "-Dorg.gradle.java.home=$jdk",
    '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8',
    '--console=plain'
)
if ($Clean) { Info '清理旧构建...'; & $gradle clean --quiet @gradleArgs }
Info '开始构建 release APK (首次需下载依赖, 请耐心等待)...'
& $gradle assembleRelease @gradleArgs
if ($LASTEXITCODE -ne 0) { Fail "gradle 构建失败 (exit $LASTEXITCODE)" }

$apk = Join-Path $AndroidDir 'app\build\outputs\apk\release\app-release.apk'
if (Test-Path $apk) {
    Ok '构建成功!'
    Write-Host ''
    Write-Host "  APK 位置: $apk"
    Write-Host "  大小:     $([math]::Round((Get-Item $apk).Length / 1MB, 1)) MB"
    Write-Host ''
    Write-Host '  安装到电视:'
    Write-Host '    方式一: adb connect <电视IP> ; adb install -r <APK路径>'
    Write-Host '    方式二: 将 APK 拷入 U 盘, 在电视的文件管理器中安装'
} else {
    Fail "构建完成但未找到 APK: $apk"
}
