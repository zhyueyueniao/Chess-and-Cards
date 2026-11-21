#!/usr/bin/env bash
# 棋牌大厅 APP 发版：自增版本号 → 构建 APK → 上传到棋牌 api-server 更新接口
# 用法：
#   bash publish_apk.sh              # versionCode 自动 +1
#   bash publish_apk.sh 12           # 指定 versionCode（需大于线上版本）
#   FORCE=true bash publish_apk.sh   # 覆盖同版本
#   CC_ADMIN_TOKEN=xxx bash publish_apk.sh   # 显式指定后台口令（默认取 tools/.gm-config.json）
set -euo pipefail

BASE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$BASE/.." && pwd)"
VERSION_FILE="$BASE/app/version.properties"
APK="$BASE/棋牌大厅.apk"
SERVER="${CC_SERVER:-https://game.65713.top:3333}"
FORCE="${FORCE:-false}"

CODE="$(sed -n 's/^versionCode=//p' "$VERSION_FILE" | head -1)"
if [[ -n "${1:-}" ]]; then
  NEW_CODE="$1"
else
  NEW_CODE=$((CODE + 1))
fi
NEW_NAME="1.0.${NEW_CODE}"
printf 'versionCode=%s\nversionName=%s\n' "$NEW_CODE" "$NEW_NAME" > "$VERSION_FILE"
echo "=== version: $CODE -> $NEW_CODE ($NEW_NAME) ==="

bash "$BASE/build_apk.sh"

SHA="$(sha256sum "$APK" | awk '{print $1}')"
echo "=== sha256: $SHA ==="

TOKEN="${CC_ADMIN_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$REPO/tools/.gm-config.json" ]]; then
  TOKEN="$(python -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("adminToken",""))' "$REPO/tools/.gm-config.json")"
fi
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: 未找到后台口令，请设置 CC_ADMIN_TOKEN" >&2
  exit 1
fi

URL="$SERVER/api/app-release/upload?versionCode=${NEW_CODE}&versionName=${NEW_NAME}&sha256=${SHA}&force=${FORCE}"
echo "=== upload: $URL ==="
curl -sS -X POST \
  -H "x-admin-token: $TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary "@$APK" \
  "$URL"
echo
echo "=== published. manifest: $SERVER/api/app-release/manifest ==="
