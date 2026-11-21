# app/ —— 装配层

只做三件事：`MainActivity` + Compose 主题、`NavHost`（把各 feature 的路由拼起来）、依赖注入装配。

- 不写业务逻辑，不写数据访问，不写提示词拼装。
- 导航图在这里；feature 只提供 `NavGraphBuilder.xxxScreen(...)`。
- DI：优先手写（省编译时间，这台机器的内存经不起 Hilt 的注解处理）。若后期确实需要 Hilt，先写 ADR。
