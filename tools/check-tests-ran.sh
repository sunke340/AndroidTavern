#!/usr/bin/env bash
# 防止"@Test 写了但没跑"。
#
# 真事：JUnit 5 会**静默跳过**返回值不是 void 的测试方法。Kotlin 里如果方法体最后一句是
# 有返回值的表达式（比如 assertIs<T>()），这个方法就变成"非 void"，JUnit 直接当它不是测试，
# 结果就是"绿灯 + 少跑一个用例"。我就这么丢过一个。
#
# 这个脚本比对「源码里 @Test 的个数」与「测试报告里的用例数」。
# 注意：不支持 @ParameterizedTest / @TestFactory（它们会一个 @Test 跑出多条用例），
# 将来真要用，在这里放行。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

python3 - <<'PY'
import glob, os, re, sys

declared = {}
for path in glob.glob('**/src/test/**/*.kt', recursive=True):
    module = path.split('/src/test/')[0]
    text = open(path, encoding='utf-8').read()
    count = len(re.findall(r'^\s*@(?:kotlin\.test\.)?Test\b', text, re.M))
    if count:
        declared[module] = declared.get(module, 0) + count

ran = {}
for path in glob.glob('**/build/test-results/test/TEST-*.xml', recursive=True):
    module = path.split('/build/')[0]
    match = re.search(r'tests="(\d+)"', open(path, encoding='utf-8').read())
    ran[module] = ran.get(module, 0) + (int(match.group(1)) if match else 0)

failures, warnings = [], []
for module, expected in sorted(declared.items()):
    actual = ran.get(module, 0)
    if actual < expected:
        failures.append(f'{module}: 源码里 {expected} 个 @Test，报告里只有 {actual} 个跑过'
                        f'（常见原因：测试方法返回了非 Unit 的值）')
    elif actual > expected:
        warnings.append(f'{module}: 报告里 {actual} 个用例，多于 {expected} 个 @Test（用了参数化测试？）')
    else:
        print(f'OK  {module}: {actual} 个测试都跑了')

for module in ran:
    if module not in declared:
        warnings.append(f'{module}: 有测试报告但没有 @Test 源码（报告是旧的吗？）')

for warning in warnings:
    print(f'WARN {warning}')
for failure in failures:
    print(f'FAIL {failure}')

if not declared:
    print('SKIP: 还没有测试源码')

sys.exit(1 if failures else 0)
PY
