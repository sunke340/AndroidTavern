package app.tavern.data.cards

/** 一张可选的卡。`id` 是应用私有目录里的文件名，稳定不变。 */
data class CardRef(
    val id: String,
    val name: String,
    /** 用户从系统文件选择器导进来的（随包带的不算）。 */
    val imported: Boolean,
    /** 角色描述，列表里当简介用。空描述是合法的（有些卡只写人格）。 */
    val description: String,
    /** 卡内嵌世界书的条目数（0 = 没有内嵌书）。 */
    val bookEntryCount: Int,
    /** 卡上的标签（`tags`）。搜索与筛选读它 —— 卡列表缓存里一并存了，不用再解 PNG。 */
    val tags: List<String> = emptyList(),
)

sealed interface ImportResult {
    data class Ok(val card: CardRef) : ImportResult
    data class Failed(val reason: String) : ImportResult
}

/**
 * 卡的读取与导入。`feature`/`app` 只依赖这个接口，换实现（以后接在线卡库、charx 包）不动界面。
 */
interface CardRepository {
    fun list(): List<CardRef>

    fun load(id: String): ByteArray?

    /** 上一次选中的卡；文件已不在就返回 null（调用方据此回落）。 */
    var selectedId: String?

    fun import(uri: android.net.Uri): ImportResult

    /** 删掉一张卡的文件。**会话不在这里处理**——由调用方决定要不要连带删。 */
    fun delete(id: String): Boolean

    /**
     * 改写一张卡的字段并写回 PNG（卡编辑）。
     *
     * 写之前先备份第一次改动前的原文件（`<名字>.bak.png`，只备份一次），
     * 再用临时文件 + 重命名原子替换 —— 写到一半断电也不会留下半张卡。
     * `IDAT` 等图片数据由 [app.tavern.core.card.PngCard.write] 保证逐字节不变。
     */
    fun saveCard(id: String, card: app.tavern.core.card.CharacterCard): Boolean
}
