#!/usr/bin/env bash
# 构建 RN 分包并打入 Android APK assets（登录 + 业务入口，离线可用）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RN_BIZ_ROOT="${RN_BIZ_ROOT:-$ROOT/../rn-biz-0.86}"
PLATFORM="android"
RN_VERSION="0.86.0"

# 计算打包 channel：
# 1) 显式传 RN_PACK_CHANNEL 时优先使用
# 2) 否则默认取当前 git 分支名（detached HEAD 回退 master）
resolve_channel() {
  if [[ -n "${RN_PACK_CHANNEL:-}" ]]; then
    echo "$RN_PACK_CHANNEL"
    return 0
  fi

  local branch
  branch="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  if [[ -z "$branch" || "$branch" == "HEAD" ]]; then
    echo "master"
    return 0
  fi

  # 分支名可能包含 '/'，目录名与配置文件名统一替换为 '-'
  echo "${branch//\//-}"
}

CHANNEL="$(resolve_channel)"

# 与 MockApiService.allEntries 保持一致
PAGE_KEYS=(login home order demo profile wallet message)

ASSETS_ROOT="$ROOT/platforms/android/app/src/main/assets"
BUNDLES_ASSETS="$ASSETS_ROOT/rn-bundles/$CHANNEL"
CONFIG_ASSETS="$ASSETS_ROOT/rn-config/channels/$CHANNEL"

echo "==> 嵌入 RN 分包（common + 业务页）"
echo "    rn-dynamic: $ROOT"
echo "    rn-biz:     $RN_BIZ_ROOT"
echo "    channel:    $CHANNEL"
echo "    pages:      ${PAGE_KEYS[*]}"

if [[ ! -d "$RN_BIZ_ROOT" ]]; then
  echo "错误: 未找到 rn-biz 仓库: $RN_BIZ_ROOT"
  exit 1
fi

if [[ ! -d "$RN_BIZ_ROOT/node_modules/react-native" ]]; then
  echo "==> 安装 rn-biz 依赖..."
  (cd "$RN_BIZ_ROOT" && yarn install)
fi

echo "==> 打包 common..."
(
  cd "$RN_BIZ_ROOT"
  yarn pack:build:common --platform "$PLATFORM" --channel "$CHANNEL"
)

DIST_ROOT="$RN_BIZ_ROOT/project/dist/bundles/$CHANNEL"
COMMON_BUNDLE="$(ls -1t "$DIST_ROOT/common/$PLATFORM"/common."$PLATFORM".*.bundle 2>/dev/null | head -1)"
if [[ -z "$COMMON_BUNDLE" || ! -f "$COMMON_BUNDLE" ]]; then
  echo "错误: 未找到 common bundle"
  exit 1
fi

COMMON_NAME="$(basename "$COMMON_BUNDLE")"
COMMON_HASH="${COMMON_NAME#common.$PLATFORM.}"
COMMON_HASH="${COMMON_HASH%.bundle}"
COMMON_ASSETS_URL="rn-bundles/$CHANNEL/common/$COMMON_NAME"

mkdir -p "$BUNDLES_ASSETS/common" "$CONFIG_ASSETS"
cp -f "$COMMON_BUNDLE" "$BUNDLES_ASSETS/common/$COMMON_NAME"

# 同步 shell 配置到当前 channel（优先复制 main，再按需改写 channel 字段）
SHELL_SRC_MAIN="$ROOT/platforms/android/app/src/main/assets/rn-config/channels/main/shell.local.json"
SHELL_SRC_PROJECT="$ROOT/project/config/channels/main/shell.local.json"
SHELL_DEST="$CONFIG_ASSETS/shell.local.json"
if [[ -f "$SHELL_SRC_MAIN" ]]; then
  cp -f "$SHELL_SRC_MAIN" "$SHELL_DEST"
elif [[ -f "$SHELL_SRC_PROJECT" ]]; then
  cp -f "$SHELL_SRC_PROJECT" "$SHELL_DEST"
fi
if [[ -f "$SHELL_DEST" ]]; then
  node -e "
    const fs = require('fs');
    const p = process.argv[1];
    const channel = process.argv[2];
    const j = JSON.parse(fs.readFileSync(p, 'utf8'));
    j.channel = channel;
    fs.writeFileSync(p, JSON.stringify(j, null, 2) + '\n');
  " "$SHELL_DEST" "$CHANNEL"
  echo "==> shell.local.json → channels/$CHANNEL"
fi

# 打包各业务 page
declare -a PAGE_META_LINES=()
for key in "${PAGE_KEYS[@]}"; do
  echo "==> 打包 $key..."
  (cd "$RN_BIZ_ROOT" && yarn pack:build "$key" --platform "$PLATFORM" --channel "$CHANNEL")
  PAGE_BUNDLE="$(ls -1t "$DIST_ROOT/$key/$PLATFORM"/"$key"."$PLATFORM".*.bundle 2>/dev/null | head -1)"
  if [[ -z "$PAGE_BUNDLE" || ! -f "$PAGE_BUNDLE" ]]; then
    echo "错误: 未找到 $key bundle"
    exit 1
  fi
  PAGE_NAME="$(basename "$PAGE_BUNDLE")"
  PAGE_HASH="${PAGE_NAME#$key.$PLATFORM.}"
  PAGE_HASH="${PAGE_HASH%.bundle}"
  PAGE_ASSETS_URL="rn-bundles/$CHANNEL/$key/$PAGE_NAME"
  mkdir -p "$BUNDLES_ASSETS/$key"
  cp -f "$PAGE_BUNDLE" "$BUNDLES_ASSETS/$key/$PAGE_NAME"
  PAGE_META_LINES+=("$key|$PAGE_NAME|$PAGE_HASH|$PAGE_ASSETS_URL")
  echo "    ✓ $key hash=$PAGE_HASH"
done

UPDATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%S.000Z")"

UPLOAD_CONFIG="$ROOT/project/config/upload.local.json"
CDN_BASE_URL="http://127.0.0.1:8787"
if [[ -f "$UPLOAD_CONFIG" ]]; then
  CDN_BASE_URL="$(node -e "
    const c = JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8'));
    process.stdout.write(String(c.baseUrl || 'http://127.0.0.1:8787').replace(/\\/+$/, ''));
  " "$UPLOAD_CONFIG")"
fi

COMMON_CDN_URL="$CDN_BASE_URL/rn/$RN_VERSION/$CHANNEL/common/$PLATFORM/$COMMON_NAME"
CDN_LOCAL="$ROOT/project/dist/cdn-local"
mkdir -p "$CDN_LOCAL/rn/$RN_VERSION/$CHANNEL/common/$PLATFORM"
cp -f "$COMMON_BUNDLE" "$CDN_LOCAL/rn/$RN_VERSION/$CHANNEL/common/$PLATFORM/$COMMON_NAME"

# 用 Node 生成 bundles.local.json 与 CDN config
PAGE_META_JOINED="$(printf '%s\n' "${PAGE_META_LINES[@]}")"
export ROOT CHANNEL PLATFORM RN_VERSION UPDATED_AT CDN_BASE_URL
export COMMON_HASH COMMON_ASSETS_URL COMMON_CDN_URL COMMON_NAME
export PAGE_META_JOINED

node <<'NODE'
const fs = require('fs');
const path = require('path');

const {
  ROOT, CHANNEL, PLATFORM, RN_VERSION, UPDATED_AT, CDN_BASE_URL,
  COMMON_HASH, COMMON_ASSETS_URL, COMMON_CDN_URL,
} = process.env;

const pageMetaLines = (process.env.PAGE_META_JOINED || '').split('\n').filter(Boolean);
const pages = pageMetaLines.map(line => {
  const [key, fileName, hash, assetsUrl] = line.split('|');
  return { key, fileName, hash, assetsUrl };
});

const pageTitles = {
  login: '登录', home: 'Home', order: '订单', demo: 'Demo',
  profile: '我的资料', wallet: '钱包', message: '消息',
};

const bundles = {
  common: [{
    key: 'common',
    name: 'common',
    componentName: 'common',
    url: `embedded://${COMMON_ASSETS_URL}`,
    assetsUrl: COMMON_ASSETS_URL,
    hash: COMMON_HASH,
    platform: PLATFORM,
    kind: 'common',
  }],
};

const cdnBundles = {
  common: [{
    key: 'common',
    name: 'common',
    componentName: 'common',
    url: COMMON_CDN_URL,
    hash: COMMON_HASH,
    platform: PLATFORM,
    kind: 'common',
  }],
};

const cdnLocal = path.join(ROOT, 'project/dist/cdn-local');

for (const page of pages) {
  const cdnUrl = `${CDN_BASE_URL}/rn/${RN_VERSION}/${CHANNEL}/${page.key}/${PLATFORM}/${page.fileName}`;
  const cdnDir = path.join(cdnLocal, 'rn', RN_VERSION, CHANNEL, page.key, PLATFORM);
  fs.mkdirSync(cdnDir, { recursive: true });
  const src = path.join(ROOT, 'platforms/android/app/src/main/assets', page.assetsUrl);
  fs.copyFileSync(src, path.join(cdnDir, page.fileName));

  bundles[page.key] = [{
    key: page.key,
    name: pageTitles[page.key] || page.key,
    componentName: page.key,
    url: `embedded://${page.assetsUrl}`,
    assetsUrl: page.assetsUrl,
    hash: page.hash,
    platform: PLATFORM,
    kind: 'page',
    dependsOn: ['common'],
  }];

  cdnBundles[page.key] = [{
    key: page.key,
    name: pageTitles[page.key] || page.key,
    componentName: page.key,
    url: cdnUrl,
    hash: page.hash,
    platform: PLATFORM,
    kind: 'page',
    dependsOn: ['common'],
  }];
}

const configAssets = path.join(ROOT, 'platforms/android/app/src/main/assets/rn-config/channels', CHANNEL);
fs.mkdirSync(configAssets, { recursive: true });

const bundlesConfig = {
  rnVersion: RN_VERSION,
  baseVersion: '0.1.0',
  channel: CHANNEL,
  updatedAt: UPDATED_AT,
  bundles,
};

fs.writeFileSync(
  path.join(configAssets, 'bundles.local.json'),
  JSON.stringify(bundlesConfig, null, 2) + '\n',
);

// 真机默认关闭远程（避免连 127.0.0.1）；有 CDN 时可改为 enabled:true
fs.writeFileSync(
  path.join(configAssets, 'remote.local.json'),
  JSON.stringify({
    enabled: false,
    baseUrl: CDN_BASE_URL,
    rnVersion: RN_VERSION,
  }, null, 2) + '\n',
);

const cdnConfig = {
  rnVersion: RN_VERSION,
  baseVersion: '0.1.0',
  channel: CHANNEL,
  updatedAt: UPDATED_AT,
  bundles: cdnBundles,
};

const cdnConfigDir = path.join(cdnLocal, 'config', RN_VERSION);
fs.mkdirSync(cdnConfigDir, { recursive: true });
fs.writeFileSync(
  path.join(cdnConfigDir, `${CHANNEL}.json`),
  JSON.stringify(cdnConfig, null, 2) + '\n',
);

console.log('==> bundles.local.json 已生成，包含:', Object.keys(bundles).join(', '));
NODE

# 写入打包 channel 标识，原生端启动时读取
echo -n "$CHANNEL" > "$ASSETS_ROOT/rn-config/build-channel.txt"
echo "==> build-channel.txt = $CHANNEL"

echo "==> 已写入 assets:"
echo "    common: $COMMON_ASSETS_URL (hash=$COMMON_HASH)"
for line in "${PAGE_META_LINES[@]}"; do
  IFS='|' read -r key _ hash assets_url <<< "$line"
  echo "    $key: $assets_url (hash=$hash)"
done
echo "==> 远程热更（联调时改 remote.local.json enabled=true）:"
echo "    GET $CDN_BASE_URL/config/$RN_VERSION/$CHANNEL"
