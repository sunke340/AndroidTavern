#!/usr/bin/env bash
# 校验 docs/ui-map.md 与代码没有漂移。
#
# 为什么需要：这份词表是"用户和 AI 说同一个东西"的唯一依据。它一旦和代码脱节，
# 就会出现"说了名字、代码里没有"或"新加了页面、词表还停在旧版"——比没有词表更糟。
#
# 查三件事（都能在 10 秒内跑完，进 CI 的 invariants job）：
#   1. 枚举里的页面/标签（SettingsPage / CardConfigPage / DetailTab）必须都在词表里；
#   2. 词表里反引号包着的**代码标识**必须在 app/ core/ data/ 里真实存在（不许有幽灵名字）；
#   3. 一批核心组件必须在词表里出现（防止有人删行）。
#
# 本机可跑：bash tools/check-ui-map.sh
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import re, sys, pathlib

doc_path = pathlib.Path('docs/ui-map.md')
if not doc_path.exists():
    sys.exit('FAIL: docs/ui-map.md 不存在')
doc = doc_path.read_text(encoding='utf-8')

sources = {}
for base in ('app/src/main/kotlin', 'core', 'data'):
    for p in pathlib.Path(base).rglob('*.kt'):
        try:
            sources[p] = p.read_text(encoding='utf-8')
        except OSError:
            pass
all_code = '\n'.join(sources.values())

problems = []

# ---- 1. 枚举里的页面/标签都要在词表里 ----
screen = sources.get(pathlib.Path('app/src/main/kotlin/app/tavern/Screen.kt'), '')
if not screen:
    problems.append('读不到 Screen.kt')

def enum_labels(enum_name, pattern):
    body = re.search(r'enum class %s\([^)]*\)\s*\{(.*?)\n\}' % enum_name, screen, re.S)
    if not body:
        return []
    return re.findall(pattern, body.group(1))

labels = []
labels += enum_labels('SettingsPage', r'[A-Za-z]+\("([^"]+)"\)')
labels += enum_labels('CardConfigPage', r'[A-Za-z]+\("([^"]+)"\)')
labels += enum_labels('DetailTab', r'[A-Za-z]+\("([^"]+)"\)')

for label in labels:
    if label not in doc:
        problems.append(f'词表缺页面/标签：{label}（枚举里有，docs/ui-map.md 里没有）')

# ---- 2. 词表里的代码标识必须在代码里存在 ----
# 只查反引号里、首字母大写、像标识符的 token（`MessagePanel`、`LiquidGlassSpec.HeaderBand`）
allow = {'Surface', 'AlwaysComplete'}  # 词表里会拿 Material3 的 Surface 当反例提
tokens = set()
for raw in re.findall(r'`([^`\n]{1,60})`', doc):
    token = raw.strip()
    head = re.split(r'[.\s(/]', token)[0]
    if re.fullmatch(r'[A-Z][A-Za-z0-9_]*', head or ''):
        tokens.add(head)
for head in sorted(tokens - allow):
    if not re.search(r'\b%s\b' % re.escape(head), all_code):
        problems.append(f'词表里的名字在代码里找不到：`{head}`')

# ---- 3. 核心组件不许被删行 ----
core = [
    'GlassDetailScaffold', 'DetailHeaderMode', 'contentSpacing', 'headerScrim', 'HeaderScrimAlpha',
    'LiquidGlassSpec', 'GlassSurface', 'CapsuleIcon',
    'MessagePanel', 'MessageActionRow', 'SendStopButton', 'ModelButton', 'ChatInputBar',
    'StreamingCursor', 'NoFocusScrollSpec', 'PendingLine', 'TypingDots',
    'ListRow', 'ListGroup', 'GroupPiece', 'RowAvatar', 'SettingLinkRow',
    'WorldBookEntryRow', 'ActiveMark', 'SegmentedRow', 'SectionLabel',
    'PrimaryAction', 'SecondaryAction', 'EmptyHint',
    'CardAvatar', 'CardFrost', 'CardHero', 'GreetingSwitchBar',
    'SheetController', 'GlassSheetScaffold', 'ConfirmController', 'ConfirmDeleteDialog',
    'ThinkingEffortSlider', 'ModelPickerRows', 'BuiltinRegex', 'InlineMarkup',
    'rubberOverscroll', 'PageStack',
]
for name in core:
    if name not in doc:
        problems.append(f'词表缺核心组件：{name}')

if problems:
    print('FAIL: docs/ui-map.md 与代码不一致：')
    for p in problems:
        print('  -', p)
    sys.exit(1)

print(f'OK: ui-map 与代码一致（页面/标签 {len(labels)} 个、代码标识 {len(tokens)} 个、核心组件 {len(core)} 个）')
PY
