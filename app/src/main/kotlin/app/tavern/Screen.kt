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
    Preset("预设"),
    Appearance("外观"),
    WorldBook("世界书"),
    Regex("正则"),
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
 * 为什么做成选项：返回的观感很主观，用户报了几轮都没对上，与其继续猜，不如让他自己拨一下。
 * 选定之后可以固化成默认，再把这个开关删掉。
 *
 * ⚠️ 硬约束（2026-11-22 从源码与逐帧录屏两头确认）：`AnimatedContent` 里**永远是"进来的那页"在上层**，
 * 给 `targetContentZIndex` 设什么都改变不了（0/1/2 和 ±1 都试过），所以**退场那页的位移看不见**。
 * 因此下面每种风格的运动都只做在"进来那页"上，退场那页只负责"别提前被销毁、别露底色"。
 *
 * - [Slide] 标准：进去时新页整屏从右滑入；返回时上一页从左侧 1/3 处滑回（当前页原位静止，被覆盖）
 * - [Fade]  交叉淡化：不位移，最不容易晕
 * - [None]  直接切换：没有任何过渡（排查时的对照组）
 */
enum class NavAnimation(val label: String) {
    Slide("标准"),
    Fade("淡入"),
    None("关闭"),
}

/**
 * 视觉效果档位（ADR 0004）。
 *
 * [Off] 默认：半透明 + 1px 描边 + 投影，**不接 Haze**，观感稳、零性能代价。
 * [Glass] 对底栏背后的内容做真模糊（Haze，Android 12+）。2026-11-22 在 vivo sdk37 上
 * 出现过"模糊被画成一条横带"，还在查，所以先不做默认。
 */
enum class VisualEffects(val label: String) {
    Glass("玻璃"),
    Off("轻量"),
}

/**
 * **真正生效**的档位：低于 API 33 没有 AGSL，玻璃档直接降成轻量。
 *
 * 所有渲染点都必须用它，不要直接比 `state.visualEffects` —— 否则老系统上会画出那套
 * 已被判定"不可用"的彩色玻璃（2026-11-23 用户要求：低版本不开放玻璃）。
 */
fun VisualEffects.effective(): VisualEffects =
    if (this == VisualEffects.Glass && !glassSupported) VisualEffects.Off else this
