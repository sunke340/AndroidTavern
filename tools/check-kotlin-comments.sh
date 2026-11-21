#!/usr/bin/env bash
# Kotlin 的块注释**可以嵌套**：注释里出现 `/*` 会再开一层，而只有对应数量的 `*/` 才能关掉。
# 结果就是"看起来正常的一行注释，把后面整个文件都吞了"，报错却是 EOF 处的
# "Unclosed comment" 加一堆莫名其妙的 Unresolved reference——很难一眼看出真凶。
#
# 真事：`/** 随包的卡（`assets/cards/*.png`） */` 里的 `/*` 吞掉了 CardStore 整个类体。
# 本脚本禁止任何文件里出现嵌套块注释，并在 EOF 处检查未闭合。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

python3 - <<'PY'
import glob, sys

problems = []


def scan(path):
    src = open(path, encoding='utf-8').read()
    i, n = 0, len(src)
    depth = 0
    line = 1
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1
            i += 1
            continue
        if depth > 0:
            if src.startswith('/*', i):
                problems.append(f'{path}:{line}: 块注释里又出现了 `/*`（Kotlin 会嵌套，后面的代码会被吞掉）')
                depth += 1
                i += 2
                continue
            if src.startswith('*/', i):
                depth -= 1
                i += 2
                continue
            i += 1
            continue
        # 不在注释里：跳过字符串，避免把字符串里的 /* 当成注释
        if src.startswith('"""', i):
            end = src.find('"""', i + 3)
            i = n if end < 0 else end + 3
            line += src.count('\n', max(0, i - 1), i)
            continue
        if c == '"':
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                if src[i] == '\n':
                    line += 1
                i += 1
            continue
        if c == "'":
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        if src.startswith('//', i):
            end = src.find('\n', i)
            i = n if end < 0 else end
            continue
        if src.startswith('/*', i):
            depth += 1
            i += 2
            continue
        i += 1
    if depth > 0:
        problems.append(f'{path}: 文件结束时还有 {depth} 层块注释没关（`/*` 没有对应的 `*/`）')


files = [f for f in glob.glob('**/*.kt', recursive=True)
         if not f.startswith(('vendor/', 'build/', '.toolchain/'))
         and '/build/' not in f]
for f in files:
    scan(f)

if problems:
    print('\n'.join(problems))
    sys.exit(1)
print(f'OK: {len(files)} 个 Kotlin 文件没有嵌套/未闭合的块注释')
PY
