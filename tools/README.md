# tools/ —— 开发期脚本

**只给开发和 CI 用，产品代码不得依赖这里。**

| 脚本 | 作用 |
|---|---|
| `check-core-purity.sh` | `core/**` 不得混入 `android.*` / `androidx.*`（铁律 #1） |
| `check-kotlin-comments.sh` | Kotlin 块注释不许嵌套/未闭合（会吞掉整段代码） |
| `check-kotlin-imports.sh` | 不许重复 `import`（本机编不了 `:app`，这类错只能脚本或 CI 抓） |
| `check-tests-ran.sh` | 单测方法必须真的跑了（`assertIs` 之类有返回值时 JUnit 会静默跳过） |
| `test-core.sh` | 本机跑全部 `:core:*` 单测 + 静默跳过守卫 |
| `gradle-local.sh` | 本机 Gradle 包装（指向 `.toolchain/`，不需要 Android SDK） |
| `check-ui-map.sh` | 校验 `docs/ui-map.md` 与代码不漂移（枚举页面都在、名字都能 grep 到） |
| `gen-ui-map.sh` | 打印从代码抽出的页面树 / 组件索引，用来刷新 UI 词表 |
| `check-ui-invariants.sh` | 校验 `docs/ui-invariants.md` 里能自动查的不变量 |
| `poll-ci.sh` | 等某个 sha 的 CI 出包（`bash tools/poll-ci.sh <sha>`） |
| `verify-apk.sh` | 无 SDK 也能验 APK 签名（`bash tools/verify-apk.sh <apk> <指纹>`） |
| `fixture/bootstrap.sh` | 重建 `vendor/SillyTavern`（只读参考 + 夹具来源） |
| `fixture/capture.mjs` | 录 golden fixture：拦截原版 fetch，抄下它自己组装的请求体 |
| `cards/make-demo-card.py` | 生成**随包演示卡** `app/src/main/assets/cards/qinghe.png`（卡面用代码画、正文在脚本里、可重跑；改完把产物一起提交） |
| `bootstrap-jdk.sh`（待建） | 免 root 装 JDK 17 到用户目录（本机跑单测用） |

脚本约定：`bash`，`set -euo pipefail`，可在仓库根目录直接跑，失败给可执行的下一步。
Python 脚本只用标准库（本机没有 Pillow 之类，也不该为一张卡引一个依赖）。
