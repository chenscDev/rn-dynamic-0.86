#!/usr/bin/env bash
# 构建 Android 内测 Debug APK（internalDebug）
#
# 与 build-pgyer-apk.sh（internalRelease）的区别：
# - Debug 包：ReactBuildConfig.DEBUG=true，支持浏览器 DevTools（Metro 按 j）
# - Release 内测包：适合蒲公英发测；Dev Menu / 热更新可用，但浏览器 DevTools 常连不上
#
# 用法（在仓库根目录）：
#   ./scripts/build-debug-apk.sh
#
# 产物：
#   project/dist/apk/RnDynamicBase-internal-debug.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/platforms/android"
OUT_DIR="$ROOT/project/dist/apk"
OUT_APK="$OUT_DIR/RnDynamicBase-internal-debug.apk"

echo "==> 仓库: $ROOT"

# --- 登录页 RN 分包打入 APK assets（冷启动无需网络）---
EMBED_SCRIPT="$ROOT/scripts/embed-login-bundles.sh"
if [[ -x "$EMBED_SCRIPT" ]]; then
  echo "==> 嵌入 login + common 分包到 assets..."
  "$EMBED_SCRIPT"
else
  echo "警告: 未找到 $EMBED_SCRIPT，跳过登录分包嵌入"
fi

# --- Java ---
# RN Gradle Plugin 要求 toolchain=17；优先 JDK 17，其次 Android Studio JBR
resolve_java_home() {
  local candidate
  # 1) 已显式设置且为 17
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    if "${JAVA_HOME}/bin/java" -version 2>&1 | grep -qE '"1?7[\. "]'; then
      echo "$JAVA_HOME"
      return 0
    fi
  fi
  # 2) Homebrew openjdk@17（Intel / Apple Silicon 通用路径）
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
  # 3) java_home -v 17
  if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    /usr/libexec/java_home -v 17
    return 0
  fi
  # 4) 回退 Android Studio JBR（可能是 21，需配合 gradle toolchain 探测）
  if [[ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]]; then
    echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    return 0
  fi
  return 1
}

export JAVA_HOME="$(resolve_java_home || true)"

if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "错误: 未找到 JDK 17。"
  echo "本机为 Intel Mac 时请用（不要装 aarch64 的 temurin cask）："
  echo "  brew install openjdk@17"
  echo "然后重新执行本脚本。"
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version

# 告知 Gradle 本机 JDK 17 安装位置（供 toolchain 匹配 languageVersion=17）
BREW_JDK17_INTEL="/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
BREW_JDK17_ARM="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
GRADLE_JDK_PATHS=""
for candidate in "$JAVA_HOME" "$BREW_JDK17_INTEL" "$BREW_JDK17_ARM"; do
  if [[ -x "${candidate}/bin/java" ]]; then
    if [[ -z "$GRADLE_JDK_PATHS" ]]; then
      GRADLE_JDK_PATHS="$candidate"
    else
      GRADLE_JDK_PATHS="${GRADLE_JDK_PATHS},${candidate}"
    fi
  fi
done
export ORG_GRADLE_JAVA_INSTALLATIONS_PATHS="$GRADLE_JDK_PATHS"

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

# Debug 包默认 arm64；可用 RN_PACK_ARCH 覆盖
ARCH="${RN_PACK_ARCH:-arm64-v8a}"
INIT_SCRIPT="$ANDROID_DIR/init.aliyun.gradle"
echo "==> assembleInternalDebug arch=$ARCH"
echo "==> Gradle 镜像: $INIT_SCRIPT"
cd "$ANDROID_DIR"
./gradlew assembleInternalDebug --no-daemon \
  -I "$INIT_SCRIPT" \
  -PreactNativeArchitectures="${ARCH}" \
  -Dorg.gradle.java.home="$JAVA_HOME" \
  -Dorg.gradle.java.installations.auto-download=false \
  -Dorg.gradle.java.installations.paths="${ORG_GRADLE_JAVA_INSTALLATIONS_PATHS:-$JAVA_HOME}"

SRC_APK="$(ls -1 "$ANDROID_DIR"/app/build/outputs/apk/internal/debug/*.apk 2>/dev/null | head -1)"
if [[ -z "$SRC_APK" || ! -f "$SRC_APK" ]]; then
  echo "错误: 未找到 internalDebug apk"
  exit 1
fi

mkdir -p "$OUT_DIR"
cp -f "$SRC_APK" "$OUT_APK"
ls -lh "$OUT_APK"

echo ""
echo "=========================================="
echo "Debug APK 已就绪:"
echo "  $OUT_APK"
echo "=========================================="
echo "安装后（浏览器 DevTools）:"
echo "  1. 电脑: cd rn-biz-0.86 && yarn start"
echo "  2. 可选 USB: adb reverse tcp:8081 tcp:8081"
echo "  3. App → 我的 → RN 调试入口 → Host=电脑IP Port=8081 Key=docs-agent"
echo "  4. 开启 DevServer → 打开分包，保持页面在前台"
echo "  5. Metro 终端按 j 打开 React Native DevTools"
echo "蒲公英发测仍用: ./scripts/build-pgyer-apk.sh"
echo "详见: project/examples/host-app/ANDROID_DEBUG_CHECKLIST.md"
