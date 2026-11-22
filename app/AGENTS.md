# AGENTS.md — `app/`

装配层，只做三件事：`MainActivity` + 主题、`NavHost`、依赖注入对象图。

**不写**：业务逻辑、数据访问、提示词拼装、界面细节（那在 `feature`）。

- 导航：在这里把各 feature 的 `NavGraphBuilder.xxxScreen(...)` 拼起来，路由常量集中在 `Routes.kt`。
- DI：**先手写**对象图（`AppGraph`），省编译时间——这台机器经不起注解处理。要上 Hilt 先写 ADR。
- `minSdk` 目标 26+；`targetSdk` 跟最新稳定版（前台服务类型、通知权限、SAF 行为都随它变）。
- 不在 `Application.onCreate` 里做重活：冷启动预算 1 秒内。

## 当前导航（2026-11-22 起）

**暂不引 `navigation-compose`**：只有三栏 + 一个二级页，用 `enum Screen` + `ChatScreen` 覆盖层就够，
返回键走 `BackHandler`。等出现深链/多级详情（角色详情→世界书→条目编辑）再引，那时写 ADR。

```
MainScreen（Scaffold + NavigationBar）
├── SessionsScreen    会话列表 → 点一行进 ChatScreen（全屏，底部栏隐藏）
├── CharactersScreen  角色卡列表（头像网格在批次 2）
└── SettingsScreen    连接 / 采样 / 外观 / 关于
```

- **聊天不占底部 tab**：它是二级页，带返回栈才符合安卓返回键。
- **世界书不占底部 tab**：它是跟着卡/会话走的编辑面，入口挂在角色详情、聊天 ⋮、设置三处（P1 再做）。
- 消息渲染收敛在 `ChatScreen.kt` 的 `MessageRow(message, style)`；`MessageStyle` 现在只有 `Card`，
  `Bubble` 是留好的接口（设置里能选，暂时回落到卡片）。
