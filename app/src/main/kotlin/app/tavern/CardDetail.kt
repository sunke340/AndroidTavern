package app.tavern

import app.tavern.core.card.CardBookEntry

/**
 * 角色详情页用的完整卡信息（网格列表只要名字/描述，这里按需再解析一次）。
 *
 * [offUids] / [forcedUids] 是**应用内**的覆盖（都按条目 uid，不按下标）：
 * 关掉卡里开着的、强制开启卡里关着的。ST 把 `enabled` 存在卡文件里，
 * 我们暂时不写回卡（写回卡属于卡编辑那一批），所以覆盖只在本机生效。
 */
data class CardDetail(
    val id: String,
    val name: String,
    val imported: Boolean,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val messageExample: String,
    val alternateGreetings: List<String>,
    val creator: String,
    val characterVersion: String,
    val spec: String?,
    val specVersion: String?,
    val tags: List<String>,
    val bookEntries: List<CardBookEntry>,
    /** 卡里开着、应用内关掉的 uid。 */
    val offUids: Set<Int>,
    /** 卡里关着、应用内强制开启的 uid（界面上标红）。 */
    val forcedUids: Set<Int>,
    /** null = 跟随全局默认（见 [MessageStyle]）。 */
    val messageStyle: MessageStyle?,
)
