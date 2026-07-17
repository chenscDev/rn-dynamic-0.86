#!/usr/bin/env bash
# 构建可上传蒲公英的 Android 内测 APK（internalRelease）
#
# 特性：
# - 原生 Tab 壳（首页 / 我的），安装即可查看，无需 Metro
# - 「我的 → RN 调试入口」可连电脑 Metro 做 RN 本地开发
# - internal flavor 允许 HTTP 访问 Metro
#
# 用法（在仓库根目录）：
#   ./scripts/build-pgyer-apk.sh
#
# 产物：
#   project/dist/apk/RnDynamicBase-internal-release.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/platforms/android"
OUT_DIR="$ROOT/project/dist/apk"
OUT_APK="$OUT_DIR/RnDynamicBase-internal-release.apk"

echo "==> 仓库: $ROOT"

# --- Java ---
# 优先 Android Studio 自带 JBR（本机 /usr/libexec/java_home 可能为空）
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  elif [[ -d "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
  elif /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  elif /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home)"
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "错误: 未找到 JDK。"
  echo "请先安装 Android Studio（自带 JBR），或安装 Temurin 17："
  echo "  brew install --cask temurin@17"
  echo "然后设置: export JAVA_HOME=/Applications/Android\\ Studio.app/Contents/jbr/Contents/Home"
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version

# --- Android SDK ---
if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [[ -d "$HOME/Android/Sdk" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi

if [[ -z "${ANDROID_HOME:-}" ]] || [[ ! -d "$ANDROID_HOME" ]]; then
  echo "错误: 未找到 Android SDK。"
  echo "请安装 Android Studio，并在 SDK Manager 中安装："
  echo "  - Android SDK Platform（与 gradle compileSdk 一致）"
  echo "  - Android SDK Build-Tools"
  echo "默认路径: ~/Library/Android/sdk"
  echo "然后: export ANDROID_HOME=\$HOME/Library/Android/sdk"
  exit 1
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
echo "==> ANDROID_HOME=$ANDROID_HOME"

# local.properties
LOCAL_PROPS="$ANDROID_DIR/local.properties"
echo "sdk.dir=$ANDROID_HOME" > "$LOCAL_PROPS"
echo "==> 已写入 $LOCAL_PROPS"

# 依赖
if [[ ! -d "$ROOT/node_modules/react-native" ]]; then
  echo "==> 安装 JS 依赖..."
  (cd "$ROOT" && yarn install)
fi

# Pgyer: arm64 only
ARCH="${RN_PACK_ARCH:-arm64-v8a}"
echo "==> assembleInternalRelease arch=$ARCH"
cd "$ANDROID_DIR"
./gradlew assembleInternalRelease --no-daemon \
  -PreactNativeArchitectures="${ARCH}" \
  -Dorg.gradle.java.home="$JAVA_HOME" \
  -Dorg.gradle.java.installations.auto-download=false

SRC_APK="$(ls -1 "$ANDROID_DIR"/app/build/outputs/apk/internal/release/*.apk 2>/dev/null | head -1)"
if [[ -z "$SRC_APK" || ! -f "$SRC_APK" ]]; then
  echo "错误: 未找到 internalRelease apk"
  exit 1
fi

mkdir -p "$OUT_DIR"
cp -f "$SRC_APK" "$OUT_APK"
ls -lh "$OUT_APK"

echo ""
echo "=========================================="
echo "蒲公英上传文件已就绪:"
echo "  $OUT_APK"
echo "=========================================="
echo "安装后:"
echo "  · 首页 / 我的：原生页，无需 Metro"
echo "RN 本地开发:"
echo "  1. 电脑: cd rn-biz-0.86 && yarn start"
echo "  2. App → 我的 → RN 调试入口"
echo "  3. Host 填 <电脑IP>（或 adb reverse 后用 localhost）Port 8081 Key home"
echo "  4. 开启 DevServer + 加载 common → 打开分包"
echo "详见: project/examples/host-app/ANDROID_DEBUG_CHECKLIST.md"
