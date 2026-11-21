# fixtures/ —— golden 夹具

**这是"兼容"二字的物证。** 没有 fixture 的兼容行为改动不接受（铁律 #2）。

```
cards/        输入：真实角色卡（PNG / JSON / charx）
chats/        输入：真实聊天记录（JSONL）
worldinfo/    输入：世界书
presets/      输入：预设
expected/     期望：原版 SillyTavern 实际发给 API 的请求体
```

规矩：
- 命名与 [../docs/compat-matrix.md](../docs/compat-matrix.md) 的 ID 对齐：`P0-5-prompt-basic.json`。
- 一个 ID 可有多份（`-basic`、`-recursion`、`-edge`）。
- **脱敏**：不出现真实 API Key（用 `sk-test`）、不出现真实用户隐私。
- 总量控制在 5MB 以内；大图先降采样。
- `expected/` 里的内容由脚本从原版 ST 录制，**不许手写**——手写就等于自证。

录制方法见 [../docs/dev/fixtures.md](../docs/dev/fixtures.md)。
