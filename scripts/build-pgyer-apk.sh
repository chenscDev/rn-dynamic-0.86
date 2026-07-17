#!/usr/bin/env bash
# 构建可上传蒲公英的 Android Debug APK
#
# 用法（在仓库根目录）：
#   ./scripts/build-pgyer-apk.sh
#
# 产物：
#   project/dist/apk/RnDynamicBase-debug.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/platforms/android"
OUT_DIR="$ROOT/project/dist/apk"
OUT_APK="$OUT_DIR/RnDynamicBase-debug.apk"

echo "==> 仓库: $ROOT"

# --- Java ---
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  elif /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home)"
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "错误: 未找到 JDK。"
  echo "请先安装 Android Studio（自带 JBR），或安装 Temurin 17："
  echo "  brew install --cask temurin@17"
  echo "然后设置: export JAVA_HOME=\$(/usr/libexec/java_home)"
  exit 1
fi
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

# Pgyer: arm64 only (~35-45MB debug)
ARCH="${RN_PACK_ARCH:-arm64-v8a}"
echo "==> assembleDebug arch=$ARCH"
cd "$ANDROID_DIR"
./gradlew assembleDebug --no-daemon -PreactNativeArchitectures="${ARCH}"

SRC_APK="$(ls -1 "$ANDROID_DIR"/app/build/outputs/apk/debug/*.apk | head -1)"
if [[ -z "$SRC_APK" || ! -f "$SRC_APK" ]]; then
  echo "错误: 未找到 debug apk"
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
echo "安装后联调:"
echo "  1. 电脑: cd rn-biz-0.86 && yarn start"
echo "  2. 手机与电脑同一 Wi-Fi"
echo "  3. 打开 App → 点「RN 调试入口」→ Host 填 <电脑IP> Port 8081 Key home"
echo "  4. 开启 DevServer + 加载 common → 打开分包"
echo "详见: project/examples/host-app/ANDROID_DEBUG_CHECKLIST.md"
