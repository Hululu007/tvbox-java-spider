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

# 3) 推送前脱敏
python scripts/sanitize.py >> logs/sync.log 2>&1 || { log "脱敏失败"; exit 1; }

# 4) 仅在有变更时提交并推送
git add -A
if git diff --cached --quiet; then
  log "无变更，跳过推送"
  exit 0
fi
git commit -m "chore: 自动刷新订阅 $(date '+%F %T')" >> logs/sync.log 2>&1
if ! git push origin HEAD:main >> logs/sync.log 2>&1; then
  log "推送失败：检查 GitHub 凭据是否过期（git credential fill / 重新设备授权）"
  exit 1
fi
log "已推送到 GitHub main"
