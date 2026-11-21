#!/usr/bin/env bash
# 重建开发期基准环境：原版 SillyTavern（只读参考 + 夹具来源）。
# vendor/ 不入库，换机器/误删后跑这个。
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

# 固定版本：夹具必须可复现，不能用浮动分支
ST_TAG="${ST_TAG:-1.19.1}"
DEST="vendor/SillyTavern"

if [ ! -d vendor ]; then mkdir -p vendor; fi

if [ -d "$DEST/.git" ]; then
  echo "已存在：$DEST（如需换版本先 rm -rf 它）"
else
  git clone --depth 1 --branch "$ST_TAG" \
    https://github.com/SillyTavern/SillyTavern "$DEST"
fi

echo
echo "原版 ST：$DEST"
grep -m1 '"version"' "$DEST/package.json" || true
node -v

cat <<'EOF'

下一步（夹具流水线，见 docs/dev/fixtures.md）：
  cd vendor/SillyTavern && npm ci && node server.js
注意：vendor/SillyTavern 只读参考，产品代码不得 import。
EOF
