#!/usr/bin/env bash
# ChatViewModel 尺寸棘轮：只降不升，防止"边拆边涨"。
#
# 判据与为什么这么定：docs/plan-chatviewmodel-split.md 的 B0 一节。
# 主指标是**非空行**而不是"有效行"——判断"哪行是注释"要排除字符串里的 `//` 与 `*`，
# 不能依赖"当前文件恰好没有多行字符串"。空行/注释量只打印，不参与判定。
#
# 用法：bash tools/check-viewmodel-size.sh [文件]   （默认 app/.../ChatViewModel.kt；
#       传别的文件是为了测试脚本本身，例如复制一份加了行的临时副本）
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

FILE="${1:-app/src/main/kotlin/app/tavern/ChatViewModel.kt}"   # 只用于"明细"那一行
BUDGET="${VIEWMODEL_BUDGET:-tools/viewmodel-budget.txt}"

if [ ! -f "$FILE" ]; then
  echo "找不到文件：$FILE" >&2
  exit 2
fi
if [ ! -f "$BUDGET" ]; then
  echo "找不到预算文件：$BUDGET" >&2
  exit 2
fi

python3 - "$FILE" "$BUDGET" <<'PY'
import re, sys

default_path, budget_path = sys.argv[1], sys.argv[2]

def measure(path):
    lines = open(path, encoding='utf-8').read().split('\n')
    total = len(lines)
    blank = sum(1 for l in lines if not l.strip())
    nonblank = total - blank
    comment = sum(1 for l in lines if l.strip() and l.strip().startswith(('//', '/*', '*', '*/')))
    fun_re = re.compile(r'^    ((?:public|private|internal|protected)\s+)?(?:suspend\s+)?(?:inline\s+)?fun\s+[A-Za-z_`][\w`]*\(')
    funs = [m for m in (fun_re.match(l) for l in lines) if m]
    return {
        'total': total,
        'blank': blank,
        'comment': comment,
        'nonblank': nonblank,
        'public_fun': sum(1 for m in funs if not m.group(1)),
        'private_fun': sum(1 for m in funs if (m.group(1) or '').strip() == 'private'),
        'extension_fun': sum(1 for l in lines if re.match(r'^    (?:private\s+|internal\s+)?fun\s+\w+\.', l)),
        'override_fun': sum(1 for l in lines if re.match(r'^    override\s+fun\s', l)),
        'nested_fun': sum(1 for l in lines if re.match(r'^        (?:private\s+|internal\s+)?(?:suspend\s+)?fun\s', l)),
        'state_update': sum(1 for l in lines if '_state.update' in l),
    }

targets = []
for raw in open(budget_path, encoding='utf-8'):
    line = raw.split('#')[0].strip()
    if not line:
        continue
    parts = line.split()
    path = parts[0]
    kv = dict(p.split('=', 1) for p in parts[1:] if '=' in p)
    targets.append((path, kv))

if not targets:
    print('预算文件里没有任何目标'); sys.exit(2)

answer = {}
for path, kv in targets:
    answer[path] = (kv, measure(path))

# 单文件模式下的额外诊断：只打印默认目标（ChatViewModel）的细节
dpath = default_path
over = []
print(f'{"非空行":>8} {"预算":>8} {"public":>7} {"预算":>7} {"update":>7} {"预算":>7}   文件')
for path, (kv, m) in answer.items():
    for key in ('nonblank', 'public_fun', 'state_update'):
        if key not in kv:
            print(f'预算条目缺 {key}：{path}'); sys.exit(2)
        if m[key] > int(kv[key]):
            label = {'nonblank': '非空行', 'public_fun': 'public fun', 'state_update': '_state.update'}[key]
            over.append(f'{path}: {label} {m[key]} > 预算 {kv[key]}（超 {m[key] - int(kv[key])}）')
    print(f'{m["nonblank"]:>8} {kv["nonblank"]:>8} {m["public_fun"]:>7} {kv["public_fun"]:>7} '
          f'{m["state_update"]:>7} {kv["state_update"]:>7}   {path}')

m = answer[dpath][1] if dpath in answer else next(iter(answer.values()))[1]
print(f'（{dpath} 明细：总行 {m["total"]} / 空行 {m["blank"]} / 注释 {m["comment"]}；'
      f'private fun {m["private_fun"]} · 扩展 {m["extension_fun"]} · override {m["override_fun"]} · 嵌套 {m["nested_fun"]}）')

if over:
    print()
    print('❌ 超出预算：')
    for o in over:
        print(f'   - {o}')
    print()
    print('   这不是"顺手又加了几行"：方案见 docs/plan-chatviewmodel-split.md。')
    print('   要么把代码放到它该在的地方（core 纯逻辑 / 独立文件），要么下调计划里的分工。')
    print('   确实必须增长 → 改 tools/viewmodel-budget.txt 并在提交信息里说明理由。')
    sys.exit(1)

print()
print('✅ 在预算内（只降不升；拆完一批记得把预算值下调）')
PY
