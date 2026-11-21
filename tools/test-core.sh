#!/usr/bin/env bash
# 跑所有纯 JVM 模块的测试 + "测试有没有真跑"的守卫。
#
# 为什么不直接 `gradle test`：聚合的 test 会配置 :app，而 :app 需要 Android SDK，
# 这台机器没有（也不该装）。所以本机只能逐模块点名。
set -euo pipefail
cd "$(dirname "$0")/.."

tools/gradle-local.sh \
    :core:model:test \
    :core:card-format:test \
    :core:prompt:test \
    :core:ai:test \
    :core:chat-format:test \
    "$@"

bash tools/check-tests-ran.sh
