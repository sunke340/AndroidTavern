#!/usr/bin/env bash
# 打印"从代码里抽出来"的清单，用来刷新 docs/ui-map.md 的页面树与组件索引。
#
# 词表里**人写的部分**（能说的名字、位置、一句话外观）没法生成，但"有哪些页面、有哪些
# 公开组件"可以——这样"新加了页面/组件却忘了写进词表"就变成一次 diff 就能看出来的事。
#
# 用法：
#   bash tools/gen-ui-map.sh          # 打印页面树 + 组件索引
#   bash tools/gen-ui-map.sh pages    # 只打印页面树
#   bash tools/gen-ui-map.sh parts    # 只打印组件索引
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-all}"

if [ "$MODE" = all ] || [ "$MODE" = pages ]; then
  echo "## 页面（从枚举生成）"
  echo
  python3 - <<'PY'
import re, pathlib
screen = pathlib.Path('app/src/main/kotlin/app/tavern/Screen.kt').read_text(encoding='utf-8')
main = pathlib.Path('app/src/main/kotlin/app/tavern/MainScreen.kt').read_text(encoding='utf-8')

def enum_body(name):
    m = re.search(r'enum class %s\([^)]*\)\s*\{(.*?)\n\}' % name, screen, re.S)
    return m.group(1) if m else ''

print('一级 tab（Screen）：', ', '.join(re.findall(r'enum class Screen \{([^}]*)\}', screen)[0].replace('\n', ' ').split(', ')))
for name in ('SettingsPage', 'CardConfigPage', 'DetailTab'):
    items = re.findall(r'([A-Za-z]+)\("([^"]+)"\)', enum_body(name))
    print(f'{name}（{len(items)}）：' + ', '.join(f'{code}={label}' for code, label in items))

dest = re.search(r'sealed interface Destination \{(.*?)\n\}', main, re.S)
if dest:
    kinds = re.findall(r'^\s{4}(?:data object|data class) (\w+)', dest.group(1), re.M)
    print(f'Destination（{len(kinds)}）：' + ', '.join(kinds))
PY
  echo
fi

if [ "$MODE" = all ] || [ "$MODE" = parts ]; then
  echo "## 公开组件（从 app/ 生成：文件名 · 组件名）"
  echo
  grep -rn --include=*.kt -E '^(internal )?fun [A-Z][A-Za-z0-9_]*\(|^(internal )?fun Modifier\.' \
    app/src/main/kotlin/app/tavern/ \
    | sed -E 's#app/src/main/kotlin/app/tavern/##; s/:[0-9]+:(internal )?fun / :: /; s/\($//; s/\(.*$//' \
    | sort
  echo
fi
