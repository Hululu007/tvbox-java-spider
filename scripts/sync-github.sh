#!/usr/bin/env bash
# 自动更新：刷新订阅 JSON -> 脱敏 -> 提交 -> 推送到 GitHub
# 由 WorkBuddy 定时任务调用，也可手动执行： bash scripts/sync-github.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p logs
log() { echo "[$(date '+%F %T')] $*" | tee -a logs/sync.log; }

# 1) 编译：显式列出 lib 下的 jar，避开 ECJ 对 lib/* 通配的解析问题
CP="$(python -c "import glob; print(';'.join(sorted(glob.glob('lib/*.jar'))))")"
if ! java -jar lib/ecj-3.36.0.jar -encoding UTF-8 -21 -cp "$CP" -d out $(find src/main/java -name '*.java') > logs/compile.log 2>&1; then
  log "编译失败，详见 logs/compile.log"
  exit 1
fi

# 2) 重新生成三批复刻订阅与站点对照
if ! java -cp "out;$CP" com.github.catvod.pan.ReplicaSites >> logs/sync.log 2>&1; then
  log "订阅生成失败"
  exit 1
fi

# 3) 重新生成云端版订阅 / 客户端 JS 插件（本机关机也能用）
python scripts/build-cloud.py >> logs/sync.log 2>&1 || { log "云端订阅生成失败"; exit 1; }
python scripts/build-hub.py >> logs/sync.log 2>&1 || { log "Hub 兼容订阅生成失败"; exit 1; }

# 4) 清除 yi.jar 宿主校验字段与公告弹窗（HUB 免费版「报天神」的根因）
python scripts/strip-jar.py >> logs/sync.log 2>&1 || { log "jar 清理失败"; exit 1; }

# 5) 推送前脱敏
python scripts/sanitize.py >> logs/sync.log 2>&1 || { log "脱敏失败"; exit 1; }

# 6) 仅在有变更时提交并推送
git add -A
if git diff --cached --quiet; then
  log "无变更，跳过推送"
  exit 0
fi
git commit -m "chore: 自动刷新订阅 $(date '+%F %T')" >> logs/sync.log 2>&1
if ! python scripts/gh-api-sync.py >> logs/sync.log 2>&1; then
  log "推送失败：检查 .gh-token 是否有效 / 重新设备授权（详见 logs/sync.log）"
  exit 1
fi
log "已通过 GitHub API 同步到 main"

# 7) 刷新 jsDelivr 缓存：@main 默认缓存 7 天，不刷就只有仓库更新、电视端拿不到
CDN_BASE="https://purge.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main"
for f in output/safe-subscription.json output/cloud-subscription.json output/hub-subscription.json output/hub-nospider-subscription.json spider/cloud_spider_v2.js spider/cloud_spider_v2.min.js spider/cloud_spider.js spider/cloud_spider.min.js; do
  curl --noproxy '*' -sS --max-time 15 "$CDN_BASE/$f" >/dev/null 2>&1 || true
done
log "已刷新 jsDelivr 缓存（云端订阅 / 插件）"
