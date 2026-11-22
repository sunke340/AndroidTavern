package app.tavern

/** 底部导航的三栏。聊天不在这里：它是从「会话」点进去的二级页（带返回栈）。 */
enum class Screen { Sessions, Characters, Settings }

/**
 * 设置里的二级页面。null = 停在一级列表。
 *
 * 为什么用枚举而不是 navigation-compose：还是那三个页 + 五个子页，状态切换足够；
 * 等出现"模型编辑 → 请求头编辑"这种三级深链再引。
 */
enum class SettingsPage(val title: String) {
    Model("模型"),
    Sampling("采样"),
    Appearance("外观"),
    WorldBook("世界书"),
    About("关于"),
}

/** 角色卡配置的二级页（从角色详情 → 配置 进入，作用域是"这张卡"）。 */
enum class CardConfigPage(val title: String) {
    WorldBook("内嵌世界书"),
    MessageStyle("对话样式"),
    Info("关于这张卡"),
}

/**
 * 消息渲染样式。
 *
 * 只有 [Card] 实现了（整行卡片，贴近原版 ST 网页观感）；
 * [Bubble] 是给以后留的开关——接口先立在设置里，换渲染只改聊天页那一个 `when`，
 * 不动消息模型、数据库、提示词。
 */
enum class MessageStyle { Card, Bubble }
