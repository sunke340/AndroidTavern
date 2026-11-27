#!/usr/bin/env bash
# 文档相对链接必须可解析（本机可跑，零依赖）。
#
# 为什么需要它：CI 的「铁律检查」里一直有这条，但**只有 CI 有** —— 而本机编不了 `:app`，
# 每次提交都要等一轮 CI，链接写错就白等两分钟（2026-11-27 真机修正那批栽在
# `docs/dev/verification/…` 里少写一层 `../`）。搬成脚本，CI 与本机用同一份判据。
#
# 只查相对链接；`http(s)` 与 `mailto:` 跳过（外链不该在 CI 里发请求）。
# `vendor/` 是只读的上游副本，不查。
#
# 用法：bash tools/check-doc-links.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

python3 - <<'PY'
import glob
import os
import re
import sys

bad = []
for f in sorted(glob.glob('**/*.md', recursive=True)):
    if f.startswith('vendor/'):
        continue
    base = os.path.dirname(f)
    for m in re.finditer(r'\]\(([^)]+)\)', open(f, encoding='utf-8').read()):
        target = m.group(1).split('#')[0]
        if not target or target.startswith(('http', 'mailto:')):
            continue
        if not os.path.exists(os.path.normpath(os.path.join(base, target))):
            bad.append(f'{f} -> {target}')

print('\n'.join(bad) if bad else 'OK: 文档相对链接都能解析')
sys.exit(1 if bad else 0)
PY
