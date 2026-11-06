#!/usr/bin/env bash
# 首次发布 / 重新授权后执行：创建仓库 -> 写入 Git 凭据 -> 推送 main。
# 前置条件：.gh-token 存在（由 gh-device-poll.sh 在用户完成设备授权后写入）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
OWNER="tlwj01"
REPO="tvbox-java-spider"
[ -f .gh-token ] || { echo "缺少 .gh-token：请先完成设备授权"; exit 1; }
TOKEN="$(cat .gh-token)"

api() {
  curl -sS --max-time 25 -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" "$@"
}

LOGIN="$(api https://api.github.com/user | python -c "import sys,json; print(json.load(sys.stdin).get('login',''))")"
echo "授权账号: $LOGIN"
[ "$LOGIN" = "$OWNER" ] || { echo "账号不匹配，停止发布"; exit 1; }

CODE="$(api -o /dev/null -w '%{http_code}' -X POST https://api.github.com/user/repos \
  -d "{\"name\":\"$REPO\",\"description\":\"Java 爬虫教学 + TVBox 网盘订阅复刻 + 自动更新\",\"private\":false,\"auto_init\":false}")"
case "$CODE" in
  201) echo "仓库已创建: $OWNER/$REPO" ;;
  422) echo "仓库已存在，直接推送" ;;
  *)   echo "创建仓库失败 HTTP $CODE"; exit 1 ;;
esac

# 凭据写入 Git Credential Manager，后续定时同步无需交互
printf 'protocol=https\nhost=github.com\nusername=%s\npassword=%s\n\n' "$OWNER" "$TOKEN" | git credential approve || \
  echo "警告：凭据写入失败，定时推送可能需要重新授权"

git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$OWNER/$REPO.git"
# 本机代理会拦截 github.com 的 git 通道（502），改走 api.github.com 的 Git Data API
python scripts/gh-api-sync.py
echo "已推送: https://github.com/$OWNER/$REPO"
