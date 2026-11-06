#!/usr/bin/env bash
# GitHub 设备授权轮询：等待用户在 https://github.com/login/device 输入 user_code 后，
# 把 access_token 保存到项目根目录 .gh-token（已被 .gitignore 排除）。
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
CLIENT_ID="178c6fc778ccc68e1d6a"
DEVICE_CODE="$(python -c "import json; print(json.load(open('.gh-device.json', encoding='utf-8'))['device_code'])")"

for i in $(seq 1 170); do
  RESP="$(curl -sS --max-time 20 -X POST -H "Accept: application/json" \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "device_code=$DEVICE_CODE" \
    --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:device_code" \
    https://github.com/login/oauth/access_token 2>/dev/null)"
  TOKEN="$(printf '%s' "$RESP" | python -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)"
  if [ -n "$TOKEN" ]; then
    printf '%s' "$TOKEN" > .gh-token
    chmod 600 .gh-token
    echo "AUTHORIZED at $(date '+%F %T') token_len=${#TOKEN}"
    exit 0
  fi
  ERR="$(printf '%s' "$RESP" | python -c "import sys,json; print(json.load(sys.stdin).get('error',''))" 2>/dev/null)"
  if [ "$ERR" != "authorization_pending" ] && [ "$ERR" != "slow_down" ] && [ -n "$ERR" ]; then
    echo "AUTH_ERROR: $ERR"
    exit 1
  fi
  sleep 5
done
echo "TIMEOUT: 设备码已过期，需重新发起授权"
exit 2
