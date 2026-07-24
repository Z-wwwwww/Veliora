#!/usr/bin/env bash
#
# Veliora Android TV APK 一键构建脚本 (macOS / Linux)
# 用法: scripts/build-apk.sh            构建 release APK
#       scripts/build-apk.sh --clean    清理后重新构建
#
# 流程: 同步网页资源 → 中和密码门补丁 → (首次)生成自签名 keystore → gradle 构建
#
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
ANDROID_DIR="$ROOT/android"
ASSETS_DIR="$ANDROID_DIR/app/src/main/assets"

info()  { printf '\033[0;36m[INFO]\033[0m %s\n' "$*"; }
ok()    { printf '\033[0;32m[ OK ]\033[0m %s\n' "$*"; }
error() { printf '\033[0;31m[FAIL]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------
# 0. 构建环境检查：JDK 21 + Android SDK
# ---------------------------------------------------------------
JAVA21_HOME=""
if JAVA21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
  : # 系统已注册的 JDK 21
else
  # Homebrew formula 版 openjdk@21（免 sudo 安装，不在 java_home 索引里）
  for candidate in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21; do
    if [ -x "$candidate/bin/java" ]; then
      JAVA21_HOME="$candidate"
      break
    fi
  done
fi
if [ -n "$JAVA21_HOME" ]; then
  export JAVA_HOME="$JAVA21_HOME"
  ok "JDK 21: $JAVA_HOME"
else
  error "未找到 JDK 21（Android Gradle Plugin 需要）。安装: brew install openjdk@21"
  exit 1
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Library/Android/sdk" "/opt/homebrew/share/android-commandlinetools" "/usr/local/share/android-commandlinetools"; do
    if [ -d "$candidate" ]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
  error "未找到 Android SDK。安装: brew install --cask android-commandlinetools && sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'"
  exit 1
fi
ok "Android SDK: $ANDROID_HOME"
echo "sdk.dir=$ANDROID_HOME" > "$ANDROID_DIR/local.properties"

# ---------------------------------------------------------------
# 1. 同步网页资源到 assets/（assets 根目录 = Web 根目录）
# ---------------------------------------------------------------
info "同步网页资源..."
rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR"
cp "$ROOT/index.html" "$ROOT/player.html" "$ASSETS_DIR/"
cp -R "$ROOT/css" "$ROOT/js" "$ROOT/libs" "$ROOT/image" "$ASSETS_DIR/"
ok "已同步 index.html player.html css/ js/ libs/ image/"

# ---------------------------------------------------------------
# 2. 密码门中和补丁（只改 assets 拷贝件，不动源码）
#    - {{PASSWORD}} → 内部固定密码的 SHA-256（js/password.js 校验格式）
#    - 预写 localStorage.passwordVerified，用户永远无感
# ---------------------------------------------------------------
info "打密码门中和补丁..."
INTERNAL_PASSWORD="veliora-apk-internal"
HASH="$(printf '%s' "$INTERNAL_PASSWORD" | shasum -a 256 | cut -d' ' -f1)"

# 与 js/password.js:55 的存储格式、js/config.js PASSWORD_CONFIG.localStorageKey 一致；
# 每次启动刷新 timestamp，绕开 90 天有效期
SEED_SCRIPT="try{localStorage.setItem('passwordVerified',JSON.stringify({verified:true,timestamp:Date.now(),passwordHash:'$HASH'}));}catch(e){}"

for page in index.html player.html; do
  f="$ASSETS_DIR/$page"
  if ! grep -q '{{PASSWORD}}' "$f"; then
    error "$page 中未找到 {{PASSWORD}} 占位符，上游模板可能已变化"
    exit 1
  fi
  # 注入哈希并紧随其后预写验证状态
  sed -i '' "s|\"{{PASSWORD}}\";|\"$HASH\"; $SEED_SCRIPT|" "$f"
done
ok "密码门已中和（内部哈希: ${HASH:0:12}…）"

# ---------------------------------------------------------------
# 3. 首次构建自动生成自签名 keystore（gitignore，不入库）
# ---------------------------------------------------------------
KEYSTORE="$ANDROID_DIR/keystore/veliora.jks"
if [ ! -f "$KEYSTORE" ]; then
  info "生成自签名 keystore（仅本机使用）..."
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -v \
    -keystore "$KEYSTORE" -storepass veliora -keypass veliora \
    -alias veliora -keyalg RSA -keysize 2048 -validity 36500 \
    -dname "CN=Veliora, OU=Local, O=Veliora, C=CN" >/dev/null 2>&1
  ok "keystore 已生成: $KEYSTORE"
fi

# ---------------------------------------------------------------
# 4. Gradle 构建（首次自动下载 wrapper 与依赖）
# ---------------------------------------------------------------
cd "$ANDROID_DIR"
GRADLE_CMD="./gradlew"
if [ ! -f gradlew ]; then
  if command -v gradle >/dev/null 2>&1; then
    info "初始化 Gradle wrapper..."
    gradle wrapper --gradle-version 8.10.2 --quiet
  else
    error "缺少 gradlew 且未安装 gradle。安装: brew install gradle，然后重新运行本脚本"
    exit 1
  fi
fi

if [ "${1:-}" = "--clean" ]; then
  info "清理旧构建..."
  $GRADLE_CMD clean --quiet
fi

info "开始构建 release APK（首次需下载依赖，请耐心等待）..."
$GRADLE_CMD assembleRelease

APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  ok "构建成功！"
  echo
  echo "  APK 位置: $APK"
  echo "  大小:     $(du -h "$APK" | cut -f1)"
  echo
  echo "  安装到电视:"
  echo "    方式一: scripts/install-tv.sh <电视IP>   （电视需开启 ADB 网络调试）"
  echo "    方式二: 将 APK 拷入 U 盘，在电视的文件管理器中安装"
else
  error "构建完成但未找到 APK: $APK"
  exit 1
fi
