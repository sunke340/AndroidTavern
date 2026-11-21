#!/usr/bin/env bash
# 检查 core/** 是否被 Android 依赖污染。
# 铁律 #1：core 必须是纯 JVM，否则本机（无 SDK、1.7GB 内存）就失去唯一的验证回路。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

fail=0

if [ ! -d core ]; then
  echo "SKIP: 还没有 core/ 目录（M0 之前正常）"
  exit 0
fi

# 1. Kotlin 源码里的 android 导入
hits=$(grep -rnE '^\s*import\s+(android|androidx)\.' --include='*.kt' core 2>/dev/null)
if [ -n "$hits" ]; then
  echo "FAIL: core 里出现 Android 导入："
  echo "$hits"
  fail=1
fi

# 2. core 的 Gradle 脚本不得套 Android 插件
hits=$(grep -rnE 'id\("(com\.android|org\.jetbrains\.kotlin\.android)' --include='build.gradle.kts' core 2>/dev/null)
if [ -n "$hits" ]; then
  echo "FAIL: core 模块使用了 Android Gradle 插件："
  echo "$hits"
  fail=1
fi

# 3. core 不得引用资源、Context、R 类
hits=$(grep -rnE '\b(android\.content\.Context|R\.(string|drawable|layout))\b' --include='*.kt' core 2>/dev/null)
if [ -n "$hits" ]; then
  echo "FAIL: core 里出现 Android 运行时符号："
  echo "$hits"
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "OK: core 是纯 JVM"
fi
exit "$fail"
