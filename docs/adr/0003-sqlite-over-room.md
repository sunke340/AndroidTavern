# ADR 0003 · 数据层用原生 SQLite，不引 Room

- 状态：接受（2026-11-22）
- 相关：[docs/dev/layout.md](../dev/layout.md) §5

## 背景

`data/AGENTS.md` 原本写的是 "Room：`CharacterEntity / MessageEntity` …"。真正开始写 M1 的存储时，
需要重新权衡一次，因为这台机器和这条流水的代价与当初设想的不一样：

- 本机（2 核 / 1.7 GiB）**编不了 Android 模块**，`:data` 的任何编译错误只能靠 CI 发现，一次 5–8 分钟。
- Room 需要 KSP：新增一个和 Kotlin 版本严格绑定的插件、一个注解处理器，
  多一个"版本对不上就整条流水红"的失败面。
- 我们的表就两张（会话、消息），SQL 全是单表 CRUD，没有关系映射、没有复杂查询、没有多态。

## 决策

`:data` 用平台自带的 `SQLiteOpenHelper` 手写 SQL，不引 Room。

- 表结构与 SQL 集中在 `data/chat/impl/TavernDatabase.kt`，一眼能读完。
- 仓库接口（`ChatRepository`）仍在 `data`，实现放 `impl/`，`feature` 只依赖接口（规则不变）。
- schema 版本号自己维护，`onUpgrade` 里写迁移。

## 代价与约束

- **没有编译期 SQL 校验**：拼错列名要到手机上才炸。缓解办法是 SQL 只出现在一个文件、
  字段名与 `ChatFile` 的原版字段对齐，并且每条语句都在 review 时逐字读过。
- 迁移要手写：从 v1 起每次改表都必须写 `onUpgrade`，并在 `docs/compat-matrix.md` 记一笔。
- 分页仍要做：`messages(sessionId, limit, beforeId)`，不许整表载入（长聊天会 OOM）。

## 什么时候推翻它

- 表关系变复杂（世界书多对多、预设快照、多角色群聊）且查询开始写不动时；
- 或者 CI 有富余、愿意吃 KSP 的构建时间。

推翻时写新 ADR 标注 supersedes 本文，并把 `data/AGENTS.md` 的表结构草案同步过去。
