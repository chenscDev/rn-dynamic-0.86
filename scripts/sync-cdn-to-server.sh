#!/usr/bin/env bash
# 将 rn-biz 本地 CDN 产物同步到演示服务器（bundle + 热更配置）
#
# 用法（在 rn-biz-0.86 或任意目录）：
#   ./scripts/sync-cdn-to-server.sh
# 或：
#   RN_BIZ_ROOT=../rn-biz-0.86 HOST=root@47.93.207.70 ./scripts/sync-cdn-to-server.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RN_BIZ_ROOT="${RN_BIZ_ROOT:-$ROOT/../rn-biz-0.86}"
HOST="${CDN_SSH_HOST:-root@47.93.207.70}"
PEM="${CDN_SSH_PEM:-$HOME/Downloads/first-pass.pem}"
REMOTE_DIR="${CDN_REMOTE_DIR:-/var/www/rn-cdn/}"
CDN_LOCAL="$RN_BIZ_ROOT/project/dist/cdn-local"

if [[ ! -d "$CDN_LOCAL" ]]; then
  echo "错误: 未找到 $CDN_LOCAL，请先 yarn pack:publish"
  exit 1
fi
if [[ ! -f "$PEM" ]]; then
  echo "错误: 未找到 SSH 密钥 $PEM"
  exit 1
fi

echo "==> rsync $CDN_LOCAL/ → $HOST:$REMOTE_DIR"
rsync -avz \
  -e "ssh -i $PEM -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" \
  "$CDN_LOCAL/" \
  "$HOST:$REMOTE_DIR"

CHANNEL="${RN_PACK_CHANNEL:-agent-docx}"
RN_VERSION="${RN_VERSION:-0.86.0}"
echo "==> 验收热更配置:"
curl -sI -A "RnDynamicBundle/0.86" \
  "http://${HOST#*@}/cdn/config/${RN_VERSION}/${CHANNEL}.json" | head -5 || true
echo "完成。App 下次进入 RN Tab 会拉新 hash（无需重装 APK，需已开启 remote.enabled）。"
