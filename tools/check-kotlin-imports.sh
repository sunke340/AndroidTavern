#!/usr/bin/env bash
# 重复 import 会让 Kotlin 报 "Conflicting import: imported name 'X' is ambiguous"，
# 而在这台机器上（编不了 Android 模块）只能等 CI 跑完 6 分钟才知道。
# 这个检查 10 秒内就把这类错误挡下来。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

python3 - <<'PY'
import glob, sys

bad = []
for path in glob.glob('**/*.kt', recursive=True):
    if path.startswith(('vendor/', '.toolchain/')) or '/build/' in path:
        continue
    seen = set()
    for no, line in enumerate(open(path, encoding='utf-8').read().split('\n'), 1):
        if not line.startswith('import '):
            continue
        if line in seen:
            bad.append(f'{path}:{no}: 重复 import -> {line.strip()}')
        seen.add(line)

if bad:
    print('\n'.join(bad))
    sys.exit(1)
print('OK: 没有重复 import')
PY
