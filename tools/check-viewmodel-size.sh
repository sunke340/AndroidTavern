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

FILE="${1:-app/src/main/kotlin/app/tavern/ChatViewModel.kt}"
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

path, budget_path = sys.argv[1], sys.argv[2]
lines = open(path, encoding='utf-8').read().split('\n')

total = len(lines)
blank = sum(1 for l in lines if not l.strip())
nonblank = total - blank
# 注释量只作信息：行首 token 判定（KDoc 的 * 也算）
comment = sum(
    1 for l in lines
    if l.strip() and l.strip().startswith(('//', '/*', '*', '*/'))
)

# 类内顶层函数（4 空格缩进）；扩展函数、override、嵌套类型里的 fun 分开统计
fun_re = re.compile(
    r'^    ((?:public|private|internal|protected)\s+)?(?:suspend\s+)?(?:inline\s+)?fun\s+[A-Za-z_`][\w`]*\('
)
ext_re = re.compile(r'^    (?:private\s+|internal\s+)?fun\s+\w+\.')
override_re = re.compile(r'^    override\s+fun\s')
nested_re = re.compile(r'^        (?:private\s+|internal\s+)?(?:suspend\s+)?fun\s')

funs = [m for m in (fun_re.match(l) for l in lines) if m]
public_fun = sum(1 for m in funs if not m.group(1))
private_fun = sum(1 for m in funs if (m.group(1) or '').strip() == 'private')
extension_fun = sum(1 for l in lines if ext_re.match(l))
override_fun = sum(1 for l in lines if override_re.match(l))
nested_fun = sum(1 for l in lines if nested_re.match(l))
state_update = sum(1 for l in lines if '_state.update' in l)

measured = {
    'nonblank': nonblank,
    'public_fun': public_fun,
    'state_update': state_update,
}

budget = {}
for raw in open(budget_path, encoding='utf-8'):
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    if '=' not in line:
        print(f'预算文件格式错（要 key=value）：{line}') ; sys.exit(2)
    k, v = line.split('=', 1)
    budget[k.strip()] = v.strip()

missing = [k for k in measured if k not in budget]
if missing:
    print(f'预算文件缺字段：{", ".join(missing)}') ; sys.exit(2)

print(f'文件            {path}')
print(f'总行 / 空行     {total} / {blank}')
print(f'注释 / KDoc 行   {comment}（只作信息）')
print(f'非空行          {nonblank}   预算 {budget["nonblank"]}')
print(f'public fun      {public_fun}   预算 {budget["public_fun"]}'
      f'   （private {private_fun} · 扩展 {extension_fun} · override {override_fun} · 嵌套 {nested_fun}）')
print(f'_state.update   {state_update}   预算 {budget["state_update"]}')

over = []
for k, label in (('nonblank', '非空行'), ('public_fun', 'public fun'), ('state_update', '_state.update')):
    try:
        limit = int(budget[k])
    except ValueError:
        print(f'预算值不是整数：{k}={budget[k]}') ; sys.exit(2)
    if measured[k] > limit:
        over.append(f'{label} {measured[k]} > 预算 {limit}（超 {measured[k] - limit}）')

if over:
    print()
    print('❌ ChatViewModel 超出预算：')
    for o in over:
        print(f'   - {o}')
    print()
    print('   这不是"顺手又加了几行"的问题：本项目的方案见 docs/plan-chatviewmodel-split.md。')
    print('   要么把代码放到它该在的地方（core 纯逻辑 / 独立文件），要么下调计划里的分工。')
    print('   确实必须增长 → 改 tools/viewmodel-budget.txt 并在提交信息里说明理由。')
    sys.exit(1)

print()
print('✅ 在预算内（三项都只降不升；拆完一批记得把预算值下调）')
PY
