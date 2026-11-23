package app.tavern.data.worldinfo.impl

import android.content.Context
import app.tavern.core.card.WorldBookFile
import app.tavern.core.card.parseWorldBook
import app.tavern.data.worldinfo.WorldBookRef
import app.tavern.data.worldinfo.WorldBookRepository
import java.io.File

/**
 * 世界书就存成原版那种 `.json` 文件（`filesDir/worldinfo/<名字>.json`），不建表。
 *
 * 为什么这里不用 SQLite：世界书是"整存整取"的文档，原版文件本身就是可移植格式，
 * 用户会拿 PC 版的书直接拖进来；拆成表反而让导入导出多一步转换（ADR 0003 说的是**聊天与消息**用 SQLite）。
 * 启用状态放 SharedPreferences：它只是这台设备上的开关，不属于书的内容。
 */
public class FileWorldBookRepository(context: Context) : WorldBookRepository {

    private val dir: File = File(context.filesDir, "worldinfo").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("world_books", Context.MODE_PRIVATE)

    override fun list(): List<WorldBookRef> {
        val enabled = enabledSet()
        return files().map { file ->
            val name = nameOf(file)
            WorldBookRef(name = name, entryCount = countEntries(name, file), enabled = name in enabled)
        }
    }

    override fun import(fileName: String, json: String): WorldBookRef {
        val rawName = fileName.substringAfterLast('/').removeSuffix(".json").removeSuffix(".JSON")
        val name = sanitize(rawName)
        require(name.isNotBlank()) { "书名为空" }

        // 先解析：不是世界书的文件不该进目录（否则设置页会列出一个 0 条的空书）
        val book = parseWorldBook(name, json)
        require(book.entries.isNotEmpty()) { "这个文件里没有世界书条目（看 entries 字段）" }

        File(dir, "$name.json").writeText(json, Charsets.UTF_8)
        return WorldBookRef(name = name, entryCount = book.entries.size, enabled = name in enabledSet())
    }

    override fun delete(name: String) {
        File(dir, "$name.json").delete()
        setEnabled(name, false)
    }

    override fun load(name: String): WorldBookFile? {
        val file = File(dir, "$name.json")
        if (!file.isFile) return null
        return runCatching { parseWorldBook(name, file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    override fun setEnabled(name: String, enabled: Boolean) {
        val current = enabledSet().toMutableSet()
        if (enabled) current += name else current -= name
        prefs.edit().putStringSet(KEY_ENABLED, current).apply()
    }

    override fun disabledEntries(name: String): Set<Int> =
        prefs.getStringSet(entryKey(name), emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    override fun setEntryDisabled(name: String, uid: Int, disabled: Boolean) {
        val current = disabledEntries(name).toMutableSet()
        if (disabled) current += uid else current -= uid
        prefs.edit().putStringSet(entryKey(name), current.map { it.toString() }.toSet()).apply()
    }

    private fun entryKey(name: String): String = "disabled\u0000$name"

    private fun files(): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.sortedBy { it.name }

    private fun nameOf(file: File): String = file.name.removeSuffix(".json")

    private fun countEntries(name: String, file: File): Int =
        runCatching { parseWorldBook(name, file.readText(Charsets.UTF_8)).entries.size }.getOrDefault(0)

    private fun enabledSet(): Set<String> = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()

    /** 文件名要能安全落盘：去掉路径分隔符之类的字符，其余（含中文）保留。 */
    private fun sanitize(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(64)

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
