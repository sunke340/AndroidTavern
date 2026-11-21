# 运维手册（怎么跑、怎么发、怎么救）

这份文件只回答"具体怎么操作"。为什么这么做在 [ADR](../adr/) 和 [device-verification](device-verification.md) 里。

---

## 1. 本机能跑什么、不能跑什么

| 命令 | 本机 | 说明 |
|---|---|---|
| `tools/check-core-purity.sh` | ✅ | 零依赖 |
| `tools/test-core.sh` | ✅ | 点名跑 `:core:*` 的测试 + 静默跳过守卫 |
| `./gradlew test` | ❌ | 聚合任务会配置 `:app`，需要 Android SDK |
| `./gradlew assembleDebug` | ❌ | 同上，且 1.7 GiB 内存必挂 swap |
| `node tools/fixture/capture.mjs` | ✅ | 需要原版 ST 在 8000 跑着 |

判断标准就一条：**碰不碰 Android 工具链**。碰的一律交给 CI。

## 2. 环境（已装好，重装看 [environment.md](../environment.md)）

```
.toolchain/jdk-17.0.21+9/     JDK（清华镜像）
.toolchain/gradle-8.11.1/     Gradle（腾讯镜像）
.toolchain/gradle-home/       GRADLE_USER_HOME（~/.gradle 不可写）
vendor/SillyTavern/           原版，只读；不入库
vendor/pw-browsers/           Playwright Chromium
vendor/pw-libs/               从 deb 里解出来的浏览器依赖
```

`tools/gradle-local.sh` 负责把这些串起来，别直接调 `gradle`。

## 3. CI 的四个出口

push 到 `main` 触发三个 job：`invariants` / `jvm-tests` / `android-build`。

| 出口 | 地址 | 用途 |
|---|---|---|
| **可用 APK** | `releases/tag/apk-latest` → `app-debug.apk` | 手机浏览器直接下载 |
| 失败日志 | `releases/tag/ci-log-jvm` / `ci-log-android` → `build.log` | **本机没有 PAT，Actions 日志页面读不到**，这是唯一可读的通道 |
| 测试报告 | Actions 页面 artifact（需登录） | 排查具体用例 |
| 提交状态 | `GET /repos/sunke340/AndroidTavern/actions/runs` | 匿名可读 |

取 APK：

```bash
curl -L -C - -o /tmp/tavern.apk \
  https://github.com/sunke340/AndroidTavern/releases/download/apk-latest/app-debug.apk
```

⚠️ GitHub release 资产在国内网络经常断在半截，**必须带 `-C -` 断点续传并重试**。

## 4. 仓库凭据

- 远端：`git@github.com:sunke340/AndroidTavern.git`
- 凭据：`.secrets/deploy_key`（部署密钥，权限仅此仓库），host key 钉在 `.secrets/known_hosts`
- `core.sshCommand` 在仓库本地配置里指定了这两者
- 旧的 PAT 已撤销（`/user` 返回 401 验证过），**不要再要 PAT**
- `.secrets/` 已 gitignore

## 5. 签名、版本号与回退点

- keystore 固定在 `keystore/tavern-debug.jks`（**故意入库**，口令与指纹见 [keystore/README.md](../../keystore/README.md)）。CI 与本地都用它，所以可以互相覆盖安装。
- `versionCode` 由 CI 注入 `TAVERN_VERSION_CODE=${{ github.run_number }}`；本机不传则回落成 1。手机会拒绝 versionCode 更低（或签名不同）的包，所以**不要手工指定比上次更小的值**。
- `buildId`：CI 注入 commit sha，本机是时间戳；App 标题下面显示前 7 位，用来确认手机上装的是哪个包。
- 出正式版要另建 release keystore，口令走 GitHub Secrets，不要复用这个。

### 验证下载到的 APK

本机没有 Android SDK，没法用 apksigner；用 AGP 缓存里的 apksig 库代替：

```bash
tools/verify-apk.sh dist/app-debug.apk 55:E0:C6:E2:19:20:61:92:51:12:04:E8:F8:AC:80:C6:19:44:91:54:26:5B:50:FA:D3:3A:B4:72:8B:96:0B:E7
```

输出 `verified=true v2=true` 且指纹与 keystore/README.md 一致，才说明"可以直接覆盖安装"。

### 打回退点

```bash
git tag apk-m0 && git push origin apk-m0   # 触发 tag 构建，产物钉在 releases/tag/apk-m0
```

tag 构建不会覆盖 `apk-latest`，资产长期有效；出问题时回装那一版即可。

## 6. 常见故障的排查顺序

1. **CI 红了**：先下 `ci-log-*` 的 `build.log`，看 `e:` / `What went wrong`。别猜。
2. **workflow 报 `Invalid workflow file`**：GitHub 的表达式校验，本地 `yaml.safe_load` 查不出来。常见原因：`hashFiles()` 用在 job 级 `if`（不允许）。
3. **run 里一个 job 都没有**：同样是 workflow 解析错，不是代码错。
4. **`:app` 报 `Cannot access class X`**：某个 `core` 模块把第三方类型暴露在公开 API 里，但依赖写成了 `implementation`。改成 `api`。
5. **本机 `./gradlew test` 报 `Failed to apply plugin 'com.android.application'`**：正常，本机没 SDK，用 `tools/test-core.sh`。
6. **Gradle 起不来，`libnative-platform.so`**：`GRADLE_USER_HOME` 指到了不可写目录。用 `tools/gradle-local.sh`。
7. **`Unclosed comment` + 一串莫名其妙的 `Unresolved reference`**：Kotlin 块注释可嵌套，注释里写了 `/*`（比如 `` `assets/cards/*.png` ``）会把后面的代码一起吞掉。`tools/check-kotlin-comments.sh` 守这条。
8. **`gradle wrapper` 卡在 "Test of distribution url failed"**：`services.gradle.org` 本机不通，用镜像生成再改回官方 URL。

## 7. 交付

- 用户通过 QQ 与我交互。APK 可以用 `qqbot_send_file` 直发，但**必须显式给 target**：`c2c:<openid>`（openid 在 `.secrets/personal.md`）。
- 微信/QQ 传文件比 GitHub 下载稳。
