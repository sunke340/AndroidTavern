# data/ —— 存储与仓库实现

- Room：`CharacterEntity / MessageEntity / LorebookEntity / PresetEntity` + DAO + Migration。
- 文件：导入导出走 SAF，`FileStore` 实现放这里。
- 密钥：`KeystoreSecretStore`，**明文不落盘、不进日志**。
- 仓库接口定义在本模块（`CharacterRepository` 等），实现放 `impl/`。

存储策略（与界面无关的硬决定）：
- **DB 是唯一真相，JSONL 只在导入/导出时生成。** 不要学原版直接拿 JSONL 当数据库——手机上频繁追加写会掉电、掉性能。
- 消息分页读，不整表载入。
- 保存给用户的数据一律可导出成原版 ST 兼容布局（这是"往返可迁移"验收标准的基础）。

注意：`data` 依赖 `core`，反向禁止。提示词逻辑不在这里。
