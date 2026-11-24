package app.tavern.data.cards.impl

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.tavern.core.card.PngCard
import app.tavern.core.card.bookEntries
import app.tavern.data.cards.CardRef
import app.tavern.data.cards.CardRepository
import app.tavern.data.cards.ImportResult
import app.tavern.data.log.AppLog
import java.io.File

/**
 * 角色卡的本地仓储：**所有卡都落成文件**，随包的和导入的不分叉。
 *
 * 为什么导入要复制成文件而不是记住 SAF 的 Uri：`ACTION_OPEN_DOCUMENT` 给的临时授权
 * 在重启后不一定还在，而且用户可能把原文件删了/移走。卡是聊天记录的根，不能悬空。
 * 复制进来之后这张卡的生命周期就归 App 管。
 *
 * 存的是**原始字节**，不重新编码 PNG——卡片数据块之外的 IDAT 必须逐字节不变（见 PngCard 注释）。
 */
class CardStore(context: Context) : CardRepository {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, CARDS_DIR)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (!dir.exists()) dir.mkdirs()
        seedBundledCards()
    }

    /** 随包的卡（`assets/cards/` 下的 PNG）在首次启动/升级后补进私有目录，之后就和导入卡一样对待。 */
    private fun seedBundledCards() {
        val seeded = prefs.getStringSet(KEY_SEEDED, emptySet()).orEmpty().toMutableSet()
        val assets = runCatching { appContext.assets.list(CARDS_DIR)?.toList().orEmpty() }
            .getOrDefault(emptyList())

        for (name in assets) {
            if (!name.endsWith(".png", ignoreCase = true) || name in seeded) continue
            val copied = runCatching {
                appContext.assets.open("$CARDS_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }.isSuccess
            if (copied) seeded += name
        }
        prefs.edit().putStringSet(KEY_SEEDED, seeded).apply()
    }

    private val seededIds: Set<String> get() = prefs.getStringSet(KEY_SEEDED, emptySet()).orEmpty()

    /**
     * 全部可选的卡，随包的排在前面。
     *
     * 名字/简介/内嵌书条数**按"字节数 + 最后修改时间"缓存**：列表页只要这三个字段，
     * 而 `PngCard.read()` 要把整张 PNG 读进来解析（真机日志：3 张卡 419ms），
     * 卡一改（或重新导入）字节数/mtime 就变，缓存自然失效。
     */
    override fun list(): List<CardRef> {
        val seeded = seededIds
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) }
            .sortedWith(compareBy({ it.name !in seeded }, { it.name }))
        val meta = readMeta()
        var dirty = false

        val cards = files.map { file ->
            val cached = meta[file.name]
            val info = if (cached != null && cached.length == file.length() && cached.modified == file.lastModified()) {
                cached
            } else {
                val parsed = runCatching { PngCard.read(file.readBytes()) }.getOrNull()
                CardInfo(
                    length = file.length(),
                    modified = file.lastModified(),
                    name = parsed?.name.orEmpty(),
                    description = parsed?.description.orEmpty(),
                    bookCount = parsed?.bookEntries()?.size ?: 0,
                ).also {
                    meta[file.name] = it
                    dirty = true
                }
            }
            CardRef(
                id = file.name,
                name = info.name.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                imported = file.name !in seeded,
                description = info.description,
                bookEntryCount = info.bookCount,
            )
        }

        // 删掉的卡不再留缓存行
        if (meta.keys.retainAll(files.mapTo(mutableSetOf()) { it.name })) dirty = true
        if (dirty) {
            writeMeta(meta)
            AppLog.i("card", "卡面信息缓存更新：${meta.size} 张")
        }
        return cards
    }

    override fun load(id: String): ByteArray? = fileFor(id)?.takeIf { it.isFile }?.readBytes()

    /** 上一次选中的卡；文件已不在就返回 null（调用方据此回落）。 */
    override var selectedId: String?
        get() = prefs.getString(KEY_SELECTED, null)?.takeIf { fileFor(it)?.isFile == true }
        set(value) {
            prefs.edit().putString(KEY_SELECTED, value).apply()
        }

    /**
     * 从系统文件选择器给的 Uri 导入一张 PNG 卡。
     *
     * 先按原版规则解析一遍：**读不出来就不是角色卡**，直接拒绝，
     * 免得用户导进来一张普通图片，然后在聊天页看到"读卡失败"。
     */
    override fun import(uri: Uri): ImportResult {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
            ?: return ImportResult.Failed("读不到这个文件（选择器授权可能已失效）")

        val card = runCatching { PngCard.read(bytes) }.getOrElse { error ->
            return ImportResult.Failed("这个 PNG 里没有角色卡数据：${error.message ?: "缺少 chara/ccv3"}")
        }

        val base = sanitize(card.name.ifBlank { "card" })
        var id = "$base.png"
        var suffix = 2
        // 同名但内容不同 → 加序号，不覆盖用户已有的卡。内容相同则视为重复导入，直接覆盖。
        while (fileFor(id)?.let { it.isFile && !it.readBytes().contentEquals(bytes) } == true) {
            if (suffix > 99) return ImportResult.Failed("同名卡超过 99 张，先删几张再导")
            id = "$base-$suffix.png"
            suffix++
        }

        val target = File(dir, id)
        val written = runCatching { target.writeBytes(bytes) }.isSuccess
        if (!written) return ImportResult.Failed("写入应用私有目录失败")

        // 手头已经解析过了，顺手把列表缓存写好：导入后马上刷新列表就不用再解析一遍
        runCatching {
            val meta = readMeta()
            meta[id] = CardInfo(
                length = target.length(),
                modified = target.lastModified(),
                name = card.name.orEmpty(),
                description = card.description.orEmpty(),
                bookCount = card.bookEntries().size,
            )
            writeMeta(meta)
        }

        return ImportResult.Ok(
            CardRef(
                id = id,
                name = card.name.ifBlank { base },
                imported = true,
                description = card.description,
                bookEntryCount = card.bookEntries().size,
            ),
        )
    }

    override fun delete(id: String): Boolean {
        val file = fileFor(id) ?: return false
        return file.isFile && file.delete()
    }

    private fun fileFor(id: String): File? =
        // 路径穿越防护：id 只能是本目录下的文件名
        id.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') && !it.contains("..") }
            ?.let { File(dir, it) }

    /** 列表页需要的三个字段（缓存值）。 */
    private class CardInfo(
        val length: Long,
        val modified: Long,
        val name: String,
        val description: String,
        val bookCount: Int,
    )

    /**
     * 缓存文件：一行一张卡，`文件名 \t 字节数 \t mtime \t 名字b64 \t 简介b64 \t 条数`。
     *
     * 为什么用 Base64：简介里可能有制表符/换行/任意 Unicode，转义只会更麻烦。
     * 放卡目录里（`.meta`）不进 `list()`（那里只认 `.png`）。
     */
    private fun readMeta(): MutableMap<String, CardInfo> {
        val map = mutableMapOf<String, CardInfo>()
        val file = File(dir, META_FILE)
        if (!file.isFile) return map
        runCatching {
            file.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split('\t')
                if (parts.size != 6) return@forEach
                val length = parts[1].toLongOrNull() ?: return@forEach
                val modified = parts[2].toLongOrNull() ?: return@forEach
                val count = parts[5].toIntOrNull() ?: return@forEach
                map[parts[0]] = CardInfo(
                    length = length,
                    modified = modified,
                    name = decodeText(parts[3]),
                    description = decodeText(parts[4]),
                    bookCount = count,
                )
            }
        }
        return map
    }

    private fun writeMeta(map: Map<String, CardInfo>) {
        runCatching {
            val text = map.entries.joinToString("\n") { (file, info) ->
                listOf(
                    file,
                    info.length.toString(),
                    info.modified.toString(),
                    encodeText(info.name),
                    encodeText(info.description),
                    info.bookCount.toString(),
                ).joinToString("\t")
            }
            File(dir, META_FILE).writeText(text, Charsets.UTF_8)
        }
    }

    private fun encodeText(raw: String): String =
        Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodeText(raw: String): String =
        runCatching { String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8) }.getOrDefault("")

    private fun sanitize(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.', ' ')
            .take(40)
        return cleaned.ifBlank { "card" }
    }

    private companion object {
        const val CARDS_DIR = "cards"
        const val META_FILE = ".meta"
        const val PREFS = "tavern.cards"
        const val KEY_SEEDED = "seeded"
        const val KEY_SELECTED = "selected"
    }
}
