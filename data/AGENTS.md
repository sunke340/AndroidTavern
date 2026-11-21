# AGENTS.md — `data/`

存储、仓库实现、密钥。**不写提示词逻辑**（那是 `core/prompt`），**不写界面**（那是 `feature`）。

## 硬规矩

- **DB 是唯一真相，JSONL 只在导入/导出时生成。** 不要学原版拿 JSONL 当数据库——手机上频繁追加写掉电、掉性能。
- 消息分页读，不整表载入内存（长聊天会 OOM）。
- 仓库**接口定义在本模块**，实现放 `impl/`；`feature` 只看得到接口。
- 密钥：`KeystoreSecretStore`。明文不落盘、不进日志、不进 fixture、不进崩溃上报。
- 导出必须能生成原版 ST 兼容布局——这是"往返可迁移"验收标准的地基。

## 表结构草案

`CharacterEntity / MessageEntity / LorebookEntity / PresetEntity`，字段命名对齐原版 JSON 字段（如 `key`、`keysecondary`、`selectiveLogic`），少一层心智转换。

依赖方向：`data → core`，反向禁止。
