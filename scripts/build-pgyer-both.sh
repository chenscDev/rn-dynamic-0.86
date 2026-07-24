#!/usr/bin/env bash
# 同时构建「测试 Debug」与「线上 Release」APK，便于蒲公英双环境扫码下载、手机并装。
#
# 包名不同，可同时安装：
#   测试: com.rndynamicbase.debug  → RnDynamic 测试
#   线上: com.rndynamicbase        → RnDynamic
#
# 用法（仓库根目录）：
#   ./scripts/build-pgyer-both.sh
#
# 产物：
#   project/dist/apk/RnDynamicBase-internal-debug.apk
#   project/dist/apk/RnDynamicBase-internal-release.apk
#
# 蒲公英：请建两个应用分别上传（见 project/docs/PGYER_DUAL_ENV.md）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/platforms/android"
OUT_DIR="$ROOT/project/dist/apk"
OUT_DEBUG="$OUT_DIR/RnDynamicBase-internal-debug.apk"
OUT_RELEASE="$OUT_DIR/RnDynamicBase-internal-release.apk"

echo "==> 仓库: $ROOT"

EMBED_SCRIPT="$ROOT/scripts/embed-login-bundles.sh"
if [[ -x "$EMBED_SCRIPT" ]]; then
  echo "==> 嵌入 login + common 分包到 assets..."
  "$EMBED_SCRIPT"
else
  echo "警告: 未找到 $EMBED_SCRIPT，跳过登录分包嵌入"
fi

resolve_java_home() {
  local candidate
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    if "${JAVA_HOME}/bin/java" -version 2>&1 | grep -qE '"1?7[\. "]'; then
      echo "$JAVA_HOME"
      return 0
    fi
  fi
  for candidate in \
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home" \
    "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
  do
    if [[ -x "${candidate}/bin/java" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  if [[ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]]; then
    echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    return 0
  fi
  return 1
}

JAVA_HOME="$(resolve_java_home)" || {
  echo "错误: 未找到 JDK 17"
  exit 1
}
export JAVA_HOME
echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version

if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [[ -d "$HOME/Android/Sdk" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi
echo "==> ANDROID_HOME=${ANDROID_HOME:-"(未设置)"}"

PROP_FILE="$ANDROID_DIR/local.properties"
if [[ -n "${ANDROID_HOME:-}" ]]; then
  echo "sdk.dir=$ANDROID_HOME" > "$PROP_FILE"
  echo "==> 已写入 $PROP_FILE"
fi

if [[ ! -d "$ROOT/node_modules/react-native" ]]; then
  echo "==> 安装 JS 依赖..."
  (cd "$ROOT" && yarn install)
fi

ARCH="${RN_PACK_ARCH:-arm64-v8a}"
INIT_SCRIPT="$ANDROID_DIR/init.aliyun.gradle"
GRADLE_ARGS=(
  --no-daemon
  -I "$INIT_SCRIPT"
  -PreactNativeArchitectures="${ARCH}"
  -Dorg.gradle.java.home="$JAVA_HOME"
  -Dorg.gradle.java.installations.auto-download=false
  -Dorg.gradle.java.installations.paths="${ORG_GRADLE_JAVA_INSTALLATIONS_PATHS:-$JAVA_HOME}"
)

cd "$ANDROID_DIR"
echo "==> assembleInternalDebug + assembleInternalRelease arch=$ARCH"
./gradlew assembleInternalDebug assembleInternalRelease "${GRADLE_ARGS[@]}"

SRC_DEBUG="$(ls -1 "$ANDROID_DIR"/app/build/outputs/apk/internal/debug/*.apk 2>/dev/null | head -1)"
SRC_RELEASE="$(ls -1 "$ANDROID_DIR"/app/build/outputs/apk/internal/release/*.apk 2>/dev/null | head -1)"
if [[ -z "$SRC_DEBUG" || ! -f "$SRC_DEBUG" ]]; then
  echo "错误: 未找到 internalDebug apk"
  exit 1
fi
if [[ -z "$SRC_RELEASE" || ! -f "$SRC_RELEASE" ]]; then
  echo "错误: 未找到 internalRelease apk"
  exit 1
fi

mkdir -p "$OUT_DIR"
cp -f "$SRC_DEBUG" "$OUT_DEBUG"
cp -f "$SRC_RELEASE" "$OUT_RELEASE"
ls -lh "$OUT_DEBUG" "$OUT_RELEASE"

echo ""
echo "=========================================="
echo "双环境 APK 已就绪（可同时上传蒲公英）:"
echo "  测试 Debug : $OUT_DEBUG"
echo "    applicationId = com.rndynamicbase.debug"
echo "    桌面名 = RnDynamic 测试"
echo "  线上 Release: $OUT_RELEASE"
echo "    applicationId = com.rndynamicbase"
echo "    桌面名 = RnDynamic"
echo "=========================================="
echo "说明: project/docs/PGYER_DUAL_ENV.md"
echo "单独打 Debug:  ./scripts/build-debug-apk.sh"
echo "单独打 Release: ./scripts/build-pgyer-apk.sh"
