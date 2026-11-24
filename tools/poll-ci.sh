#!/usr/bin/env bash
# 等本次提交的 CI：只看这个 sha 的 run（历史上失败过的 run 会让"还没跑完"被误判成失败）。
#
# 用法：bash tools/poll-ci.sh <sha 或 sha 前缀>
# 退出码：0 = apk-latest 已更新到这个 sha；2 = CI 失败；3 = 超时。
#
# 为什么写进仓库：以前这份脚本只活在 /tmp，换机器/换会话就得重写一遍（2026-11-24）。
set -u
SHA="${1:?用法: bash tools/poll-ci.sh <sha>}"
REPO="${TAVERN_REPO:-sunke340/AndroidTavern}"
TRIES="${TAVERN_POLL_TRIES:-25}"
SLEEP="${TAVERN_POLL_SLEEP:-40}"

for i in $(seq 1 "$TRIES"); do
  REL=$(curl -s --max-time 25 "https://api.github.com/repos/$REPO/releases/tags/apk-latest")
  BODY=$(printf '%s' "$REL" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('body') or '')" 2>/dev/null)
  if printf '%s' "$BODY" | grep -q "$SHA"; then
    echo "[$(date +%H:%M:%S)] APK READY for $SHA"
    printf '%s' "$REL" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for a in d.get('assets',[]):
    if a['name'].endswith('.apk'): print('asset', a['name'], a['size'], a['updated_at'])
"
    exit 0
  fi

  RUNS=$(curl -s --max-time 25 "https://api.github.com/repos/$REPO/actions/runs?per_page=10")
  SUMMARY=$(printf '%s' "$RUNS" | SHA="$SHA" python3 -c "
import sys,json,os
sha=os.environ['SHA']
d=json.load(sys.stdin)
for r in d.get('workflow_runs',[]):
    if r.get('head_sha','').startswith(sha):
        print(r['id'], r['status'], r['conclusion'], r['head_branch'])
" 2>/dev/null)
  echo "[$(date +%H:%M:%S)] try#$i"
  [ -n "$SUMMARY" ] && echo "$SUMMARY"
  if printf '%s' "$SUMMARY" | grep -q " failure "; then
    echo "CI FAILED for $SHA"
    exit 2
  fi
  sleep "$SLEEP"
done
echo TIMEOUT
exit 3
