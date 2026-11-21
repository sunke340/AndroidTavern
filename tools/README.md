# tools/ —— 开发期脚本

**只给开发和 CI 用，产品代码不得依赖这里。**

| 脚本 | 作用 |
|---|---|
| `check-core-purity.sh` | 检查 `core/**` 是否混入 Android 依赖（铁律 #1） |
| `fixture/bootstrap.sh` | 重建 `vendor/SillyTavern`（只读参考 + 夹具来源） |
| `fixture/`（待建） | 假 API + 录制脚本，把原版的请求体变成 golden fixture |
| `bootstrap-jdk.sh`（待建） | 免 root 装 JDK 17 到用户目录（本机跑单测用） |

脚本约定：`bash`，`set -euo pipefail`，可在仓库根目录直接跑，失败给可执行的下一步。
