package app.tavern

import app.tavern.core.card.CardBookEntry

/**
 * 角色详情页用的完整卡信息（网格列表只要名字/描述，这里按需再解析一次）。
 *
 * [disabledEntries] 是**应用内**的"关掉哪几条内嵌世界书条目"；
 * ST 把 `enabled` 存在卡文件里，我们暂时不动卡（写回卡属于卡编辑那一批）。
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
    val disabledEntries: Set<Int>,
    /** null = 跟随全局默认（见 [MessageStyle]）。 */
    val messageStyle: MessageStyle?,
)
