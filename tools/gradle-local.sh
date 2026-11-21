#!/usr/bin/env bash
# 在本机跑 Gradle。绕开两个只在这台机器上存在的问题：
#   1. ~/.gradle 不可写（沙箱只允许工作区 + 临时目录）→ GRADLE_USER_HOME 指到 .toolchain
#   2. services.gradle.org 在本机拉不动（wrapper 的发行版下载会卡死）→ 直接用解压好的发行版
#
# 用法：tools/gradle-local.sh :core:prompt:test
# CI 上仍然用 ./gradlew（官方 URL + GHA 缓存），两边跑的是同一个 8.11.1。
set -euo pipefail
cd "$(dirname "$0")/.."

JDK_DIR=$(ls -d .toolchain/jdk-17* 2>/dev/null | head -1 || true)
GRADLE_BIN=$(ls .toolchain/gradle-8.11.1/bin/gradle 2>/dev/null | head -1 || true)

if [ -z "$JDK_DIR" ] || [ -z "$GRADLE_BIN" ]; then
    cat >&2 <<'EOF'
缺少本机工具链。装法见 docs/environment.md：
  .toolchain/jdk-17...        Temurin 17（清华镜像）
  .toolchain/gradle-8.11.1/   Gradle 发行版（腾讯镜像）
  .toolchain/gradle-home/     GRADLE_USER_HOME
EOF
    exit 1
fi

export JAVA_HOME="$PWD/$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$PWD/.toolchain/gradle-home"

exec "$PWD/$GRADLE_BIN" "$@"
