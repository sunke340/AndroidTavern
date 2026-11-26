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
# 纯 JVM 模块的结果在 build/test-results/test/；
# Android 模块（:data / :app）是 build/test-results/testDebugUnitTest/ —— 两个都要看，
# 否则给 :data 加测试会被这条守卫误判成"一个都没跑"（2026-11-27 真踩）。
PATTERNS = ('**/build/test-results/test/TEST-*.xml',
            '**/build/test-results/testDebugUnitTest/TEST-*.xml')
for pattern in PATTERNS:
    for path in glob.glob(pattern, recursive=True):
        module = path.split('/build/')[0]
        match = re.search(r'tests="(\d+)"', open(path, encoding='utf-8').read())
        ran[module] = ran.get(module, 0) + (int(match.group(1)) if match else 0)

failures, warnings = [], []
for module, expected in sorted(declared.items()):
    # 本机编不了 Android 模块（没有 SDK）→ 那个模块的 build/ 根本不存在。
    # 这种情况是"这台机器没跑它"，不是"测试被静默跳过"，打印一行 SKIP 就好；
    # 只要 build/ 存在（CI 里一定会存在），就照旧严格核对。
    if not os.path.isdir(os.path.join(module, 'build')):
        print(f'SKIP {module}: 这台机器没构建过它（没有 build/），跳过核对 {expected} 个 @Test')
        continue
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
