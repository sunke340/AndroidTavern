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

## 5. 已定

- **出包方式：GitHub Actions CI**（2026-11-21 定）。本机只做「写码 + 纯逻辑单测」，不装 Android SDK。
- GitHub 仓库地址待提供；CI 需要 `.github/workflows/build.yml` + `assembleDebug` + artifact 上传。
- 真机调试暂不配（Tailscale）。上真机时先走「CI 出包 → 下载 APK → 直接装」。

不装 SDK 的代价：本机跑不了 `./gradlew`，所以单测若也要在本机跑，仍需一个 JDK（约 190MB，禁 root 可装）；
或者干脆把单测也交给 CI——但那样"改一行等两秒"的回路就没了。建议保留本机 JDK，不装 SDK。

---

## 6. 2026-11-22 实测：原版 ST + 无头浏览器能在这台机器上跑

之前判断"这台机器不能跑浏览器"，实测下来**可以**，代价是三个小绕行：

| 问题 | 绕法 |
|---|---|
| 缺 Chromium 的 34 个系统库，且无 `sudo` | `apt-get download` 33 个 deb → `dpkg-deb -x` 到 `vendor/pw-libs`（20 MB），用 `LD_LIBRARY_PATH` 喂给浏览器 |
| 浏览器没装在系统里 | `PLAYWRIGHT_BROWSERS_PATH=vendor/pw-browsers`，全在仓库内（~280 MB，`.gitignore`） |
| `~/.npm` 有 root 占用的缓存，`npm ci` 直接失败（exit 243） | `npm_config_cache=vendor/.npm-cache` |

实测结果：

- 原版 ST 1.19.1 在 Node 24 下正常启动（webpack 前端库编译 19 s），`GET /` 返回 200。
- Playwright 无头 Chromium 正常加载完整前端（扩展全部激活），**录制 fixture 成功**。
- 同时跑 dsh（~500 MB）+ ST（~300 MB）+ Chromium（~250 MB），靠 swap 撑住；慢，但一次录制 ~25 秒，可接受。
- 两次录制结果**逐字节一致**，见 [dev/fixtures.md](dev/fixtures.md)。

结论修正：这台机器不仅"能写文档 + 跑纯 JVM 单测"，还能**跑原版 ST 并生成兼容性基准**。仍然不能做的只有：Android 构建（内存）和模拟器（无 KVM）。

## 7. Git 访问方式（已切换为 SSH 部署密钥）

| 项 | 现状 |
|---|---|
| remote | `git@github.com:sunke340/AndroidTavern.git` |
| 凭据 | `.secrets/deploy_key`（私钥，600，gitignore），权限仅限本仓库 |
| host key | `.secrets/known_hosts`，已与 `api.github.com/meta` 公布的清单核对后钉死 |
| 旧 PAT | 已撤销（用 `/user` 验证返回 401），`git-credentials` 已删 |

**关于 443 端口**：`ssh.github.com:443` 实测可用（同一把部署密钥认证成功，host key 与 github.com 相同），
但本机 **22 端口直连正常**（握手 ~3s，推送成功），所以默认不切。只有在这类情况下才需要临时改：

```bash
# 22 超时 / 被拒时的一行逃生口
git config core.sshCommand "ssh -i $PWD/.secrets/deploy_key -o IdentitiesOnly=yes \
  -o UserKnownHostsFile=$PWD/.secrets/known_hosts -p 443"
git remote set-url origin git@ssh.github.com:sunke340/AndroidTavern.git
```

不默认切的原因：443 需要额外维护 `[ssh.github.com]:443` 的 known_hosts 条目，且一旦出问题就有两种
访问路径要排查。**22 能通就别加变量。**

## 8. 本机工具链（已装好，2026-11-22）

| 东西 | 位置 | 来源 |
|---|---|---|
| JDK 17 (Temurin 17.0.21+9) | `.toolchain/jdk-17.0.21+9/` | 清华 Adoptium 镜像（GitHub Release 直连卡死） |
| Gradle 8.11.1 | `.toolchain/gradle-8.11.1/` | 腾讯 gradle 镜像 |
| Gradle 用户目录 | `.toolchain/gradle-home/` | 因为 `~/.gradle` 不可写（沙箱） |

用法：

```bash
tools/gradle-local.sh :core:prompt:test
```

第二个跑的耗时 **2.2 秒**（daemon + 增量），首次编译 ~100 秒。

⚠️ daemon 会常驻 ~600 MB。这台机器总内存 1.7 GiB，跑完记得放掉：

```bash
tools/gradle-local.sh --stop
```

三个坑（脚本/文档里都已处理）：

1. **`~/.gradle` 不可写** → Gradle 初始化 native 服务时就挂（`Failed to load native library 'libnative-platform.so'`）。
   必须 `GRADLE_USER_HOME` 指到工作区内。
2. **`services.gradle.org` 在本机拉不动**（307 到 GitHub Release 后 0 字节）→ `gradle wrapper` 任务会因为
   "Test of distribution url failed" 直接失败。解法：用腾讯镜像 URL 生成 wrapper，再把
   `gradle-wrapper.properties` 改回官方 URL（CI 在 GHA 网络里下载正常），本机则绕开 wrapper 直接调发行版。
3. **`unzip` 不存在** → 用 `python3 -m zipfile` 或 `jar xf` 解压。

为什么 wrapper 和本机脚本并存：`./gradlew` 是给 CI 和别人克隆用的（标准、可复现）；本机的
`tools/gradle-local.sh` 只解决"这台机器连不上 Gradle 官方下载"。两者跑的是同一个 8.11.1。
