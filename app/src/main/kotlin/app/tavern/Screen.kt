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

/**
 * 二级页的转场风格。
 *
 * 为什么做成选项：返回的观感很主观，用户报了三轮都没对上，与其继续猜，不如让他自己拨一下。
 * 选定之后可以固化成默认，再把这个开关删掉。
 *
 * - [Slide]  标准安卓：**同一时刻只有一层在动**（进去时新页从右滑入、返回时当前页向右滑出，另一层不动）
 * - [Stack]  层叠：两层同时动（新页推入、旧页退 1/4），"卡片堆"的手感
 * - [Fade]   淡入淡出：不位移，最不容易晕
 * - [None]   直接切换：没有任何过渡（排查时当对照组用）
 */
enum class NavAnimation(val label: String) {
    Slide("标准"),
    Stack("层叠"),
    Fade("淡入"),
    None("关闭"),
}
