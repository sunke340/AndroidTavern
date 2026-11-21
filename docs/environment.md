# 本机能不能开发？——实测结论

实测时间 2026-11-21，主机 = 阿里云 VM（Ubuntu 24.04.5，x86_64，2 vCPU / 1.7GiB RAM）。

## 1. 现状（全部为实测）

| 项 | 结果 |
|---|---|
| JDK / javac | ❌ 未安装，全盘无 JDK |
| Android SDK / adb / emulator | ❌ 未安装 |
| Gradle / Kotlin | ❌ 未安装 |
| Android Studio | ❌ 无（本来也没图形界面） |
| Node / pnpm | ✅ Node 24.22、pnpm 12.18 |
| Python / git | ✅ 3.12 / 2.43 |
| 磁盘 | ✅ `/` 40G，空闲 27G |
| 内存 | ⚠️ 总计 1.7GiB，**当前可用仅 876MiB**（dsh 本体占 ~500MB），swap 2GiB 已用 368MB |
| 虚拟化 | ❌ 无 `/dev/kvm`，CPU 无 vmx/svm → **模拟器不可能** |
| root | ❌ `no new privileges` 置位，`sudo` 不可用 → 不能 `apt install` |
| 网络 | ✅ mavenCentral / dl.google.com / npm 均 200，下载 ~1.4MB/s；阿里云、腾讯镜像可用 |

## 2. 逐项判定

| 诉求 | 结论 | 说明 |
|---|---|---|
| 写代码、git、重构 | ✅ 完全可行 | 就是编辑 + 版本管理 |
| 纯 JVM 单测（PromptBuilder、世界书激活、格式编解码） | ✅ 可行 | 前提：`:core:*` 模块不依赖 android.*（规划里已强制） |
| 跑原版 ST 做 golden fixture | ✅ 可行 | `node server.js`，常驻 ~300–500MB，与 dsh 一起跑会紧但能跑 |
| 本机装 JDK + SDK | ✅ 可行，**免 root** | 全部解压到用户目录，约 4–6GB |
| 本机 `assembleDebug` 出 APK | ⚠️ 可能，但难看 | Gradle daemon + Kotlin daemon 要 1.5–2GiB，可用只有 876MiB → 必然靠 swap 硬撑。首次构建估 20–40 分钟，且随时可能 OOM |
| 模拟器 / AVD | ❌ 不可能 | 无 KVM；无加速的 x86 镜像在 2 核 1.7G 上等于不可用 |
| 真机联调（adb logcat / 热装） | ⚠️ 有条件 | 本机在云 NAT 后（172.17.x），手机要先能连到它：装 Tailscale 走 `adb pair`；否则走"出包 → 递 APK → 应用内导出日志" |

**一句话**：这台机器是合格的**代码仓库 + 逻辑测试机**，不是合格的 **Android 构建机**，更不是运行环境。它最缺的不是工具，是内存和 KVM。

## 3. 免 root 装工具链（已验证可下载，未执行）

```bash
mkdir -p ~/toolchain && cd ~/toolchain
# JDK 17（Temurin，用户态）
curl -L -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk17.tar.gz && export JAVA_HOME=$PWD/jdk-17*

# Android cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip -d ~/toolchain/sdk-tmp
mkdir -p ~/toolchain/android-sdk/cmdline-tools
mv ~/toolchain/sdk-tmp/cmdline-tools ~/toolchain/android-sdk/cmdline-tools/latest
export ANDROID_HOME=~/toolchain/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

内存紧的房间要这么调（`gradle.properties`）：

```properties
org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m
kotlin.daemon.jvmargs=-Xmx768m
kotlin.compiler.execution.strategy=in-process
org.gradle.workers.max=1
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.configureondemand=true
```

同时把 dsh 那 500MB 也算进预算：**构建时不要指望还有余量**。

## 4. 推荐的工作方式（省事且稳）

```
写代码（本机，随时，零成本）
      │
      ├─ 纯逻辑改动 → 本机 ./gradlew :core:prompt:test（只需 JDK，几十秒）
      │
      ├─ 要出 APK → push 到 GitHub，Actions ubuntu-latest 出 debug APK（免费额度，4C16G，5 分钟）
      │                └─ artifact 下载 → 直接在手机上装
      │
      └─ 要调 UI / 看 logcat → 手机装 Tailscale，本机 adb pair 后 adb logcat 实时看
```

理由：CI 出包把"2 核 1.7G 编译 Compose"这个最痛的点彻底移走；本机只保留"改一行等两秒"的单元测试回路。真机联调则是可选加速项，不是必需项。

如果坚持本机出包：可行，但要接受"首次 20–40 分钟、偶发 OOM、增量构建也要 1–3 分钟"。

## 5. 需要你拍板的事

1. **出包方式**：CI（推荐）还是本机硬扛？
2. **真机调试**：要不要配 Tailscale + 无线 adb？（需要你在手机上装 Tailscale）
3. **GitHub 仓库**：新建 public/private？有 `gh` 或 token 吗？（CI 方案的前提）
