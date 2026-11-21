# debug 签名（公开的开发用密钥）

这个目录里的 keystore **故意入库**，不是泄漏。

## 为什么放仓库

Android 的覆盖安装要求「新包签名 == 已装包签名」。CI 每次 runner 都是干净的，
AGP 会用 `~/.android/debug.keystore`——但那个文件在 CI 上每次重新生成，
于是**每一次 CI 构建的 APK 签名都不同**，用户必须卸载再装（数据全丢）。

把 keystore 固定进仓库，签名就固定了：CI 与本地产出的 APK 可以互相覆盖安装。

## 它是什么

| 项 | 值 |
|---|---|
| 文件 | `tavern-debug.jks`（JKS，2174 字节） |
| 别名 | `tavern-debug` |
| storePassword / keyPassword | `taverndebug` |
| 算法 | RSA 2048，有效期 10000 天 |
| 证书 SHA-256 | `55:E0:C6:E2:19:20:61:92:51:12:04:E8:F8:AC:80:C6:19:44:91:54:26:5B:50:FA:D3:3A:B4:72:8B:96:0B:E7` |

口令明文写在仓库里，**这是刻意的**：debug 密钥不承担任何保密职责，
写出来才能让任何人（包括 CI）复现出同一签名的包。

## 红线

- 只用于 **debug** 构建。`buildTypes.release` 不要挂它。
- 不要拿它给真实用户发版；上架 / 正式分发要用单独的 release keystore（口令走 Secret）。
- 它一旦更换，所有已装用户都要卸载重装。**不要随便重新生成。**

## 重建（只在真的需要时）

```bash
keytool -genkeypair -v -keystore keystore/tavern-debug.jks -storetype JKS \
  -alias tavern-debug -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass taverndebug -keypass taverndebug \
  -dname "CN=Android Tavern Debug, O=AndroidTavern, C=CN"
```

换掉之后记得同步更新本文件的指纹，并在 PR 里说明「用户需要卸载重装」。
