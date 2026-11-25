#!/usr/bin/env bash
# 校验 docs/ui-invariants.md 里那些"能自动查"的不变量。
#
# 为什么要有：这些坑每一条都真机上坏过（页头材质/间距、超高气泡按不到正文、选字拖动列表…），
# 而本机编不了 :app、也没有模拟器 —— 只能靠**结构级/文本级**的检查兜底。
# 判据不成立的条目**不许**写进表里（见 docs/ui-invariants.md 的验收规则）。
#
# 已完成：INV-01 / INV-02 / INV-03 / INV-05 / INV-07
# 待做（在 docs/ui-invariants.md 里标着「未做」）：INV-04（玻璃三铁律的文本近似）、
#   INV-06（可滚面都挂橡皮筋）、INV-08（页面里不许写字面色值）
#
# 本机可跑：bash tools/check-ui-invariants.sh
# 自测（判据会不会红）：把被测目录指到一份改坏的副本，例如
#   cp -r app /tmp/selftest && <改坏> && \
#   UI_INVARIANTS_APP=/tmp/selftest/src/main/kotlin/app/tavern bash tools/check-ui-invariants.sh
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import os, re, sys, pathlib

APP = pathlib.Path(os.environ.get('UI_INVARIANTS_APP', 'app/src/main/kotlin/app/tavern'))
problems = []

def blank_comments(text):
    """把注释内容替换成空格（保留换行与偏移），否则注释里提到 `Surface(...)` 会误报。"""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        if text.startswith('//', i):
            while i < n and text[i] != '\n':
                out[i] = ' '
                i += 1
        elif text.startswith('/*', i):
            while i < n and not text.startswith('*/', i):
                if text[i] != '\n':
                    out[i] = ' '
                i += 1
            for k in range(i, min(i + 2, n)):
                out[k] = ' '
            i += 2
        else:
            i += 1
    return ''.join(out)

def read_code(path):
    return blank_comments(path.read_text(encoding='utf-8'))

def line_of(code, token):
    return code.count('\n', 0, code.index(token)) + 1

# ---- INV-01：页头材质与首项让位只由骨架决定 ----
# 骨架把最终 top 交出来（状态栏 + 页头 + contentSpacing），页面只写 padding.calculateTopPadding()。
# 页头自己加一笔 = 两次事故（首项多 12dp / 世界书列表少 64dp）；headerMode 吃默认值 =
# 页面在判据里变盲区（6f4dd53 的卡配置分发就是这么静默坏掉的）。
SCAFFOLD = 'GlassDetailScaffold('
for p in sorted(APP.glob('*.kt')):
    full = read_code(p)
    if SCAFFOLD not in full or p.name == 'DetailPage.kt':   # 骨架自己合法地用这些名字
        continue
    for token in ('contentTopInset', 'frostedHeader', 'DetailScrollUnderTop',
                  'DetailHeaderHeight', 'statusBarTop('):
        if token in full:
            problems.append(
                f'INV-01: {p.name}:{line_of(full, token)} 用了 {token}'
                f'（页头与首项让位只能由骨架给，页面不许自己算）'
            )
    if 'calculateTopPadding() +' in full:
        problems.append(
            f'INV-01: {p.name}:{line_of(full, "calculateTopPadding() +")} 在 padding 上自己加了一笔'
            f'（只能写 padding.calculateTopPadding()，额外留白走 contentSpacing）'
        )
    calls, modes = full.count(SCAFFOLD), full.count('headerMode =')
    if modes < calls:
        problems.append(
            f'INV-01: {p.name} 有 {calls} 处 GlassDetailScaffold 调用，只有 {modes} 处显式传 headerMode'
            f'（不许吃默认值：默认值会让页面在判据里变盲区）'
        )

# ---- INV-02：聊天页气泡底板不是 Material3 Surface ----
# Surface 的 clip(shape) 会让超高气泡的子树在约 8k px 之后收不到触摸（选字完全无反应、画面正常）。
chat = APP / 'ChatScreen.kt'
src = chat.read_text(encoding='utf-8')
code = blank_comments(src)

# 函数区间：以行首 `private fun X(` 为界
bounds = [(m.start(), m.group(1)) for m in re.finditer(r'^private fun (\w+)\(', src, re.M)]
bounds.append((len(src), '<eof>'))
def enclosing(pos):
    for (start, name), (end, _) in zip(bounds, bounds[1:]):
        if start <= pos < end:
            return name
    return '?'
for m in re.finditer(r'(?<![A-Za-z])Surface\(', code):
    owner = enclosing(m.start())
    if owner != 'RequestDialog':
        line = code.count('\n', 0, m.start()) + 1
        problems.append(
            f'INV-02: ChatScreen.kt:{line} 在 {owner} 里用了 Material3 Surface（消息底板必须是 MessagePanel）'
        )

# ---- INV-03：选字不许拖动列表 ----
if 'LocalBringIntoViewSpec provides NoFocusScrollSpec' not in src:
    problems.append('INV-03: ChatScreen.kt 没把 LocalBringIntoViewSpec 换成 NoFocusScrollSpec（选字会拖动列表）')

# ---- INV-05：弹层只能用抽屉宿主，不许 ModalBottomSheet / 页面内 composed ----
modal = sorted(str(p) for p in APP.glob('*.kt') if 'ModalBottomSheet' in p.read_text(encoding='utf-8'))
if modal:
    problems.append('INV-05: 出现了 ModalBottomSheet（采不到内容层，只能走 SheetController）：' + ', '.join(modal))
# GlassSheetScaffold 只允许出现在抽屉宿主与一级页里
allowed = {'ActionSheet.kt', 'MainScreen.kt'}
for p in APP.glob('*.kt'):
    if 'GlassSheetScaffold(' in p.read_text(encoding='utf-8') and p.name not in allowed:
        problems.append(f'INV-05: {p.name} 直接画了 GlassSheetScaffold（页面只该 sheet.show(...)）')

# ---- INV-07：底部让位取并集，不许相加 ----
for p in APP.glob('*.kt'):
    text = p.read_text(encoding='utf-8')
    for bad in ('navBottom + imeBottom', 'imeBottom + navBottom'):
        if bad in text:
            problems.append(f'INV-07: {p.name} 把导航栏与键盘相加了（应取 max）：{bad}')

if problems:
    print('FAIL: 不变量被破坏：')
    for p in problems:
        print('  -', p)
    print()
    print('别硬改检查：先看 docs/ui-invariants.md 里这条的"为什么"，再决定是实现错了还是判据该调整。')
    sys.exit(1)

print('OK: 已实现的不变量都成立（INV-01 页头入口 / INV-02 消息底板 / INV-03 选字不拖列表 / '
      'INV-05 弹层 / INV-07 让位并集）')
PY
