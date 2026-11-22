package app.tavern

/** 底部导航的三栏。聊天不在这里：它是从「会话」点进去的二级页（带返回栈）。 */
enum class Screen { Sessions, Characters, Settings }

/**
 * 消息渲染样式。
 *
 * 只有 [Card] 实现了（整行卡片，贴近原版 ST 网页观感）；
 * [Bubble] 是给以后留的开关——接口先立在设置里，换渲染只改聊天页那一个 `when`，
 * 不动消息模型、数据库、提示词。
 */
enum class MessageStyle { Card, Bubble }
