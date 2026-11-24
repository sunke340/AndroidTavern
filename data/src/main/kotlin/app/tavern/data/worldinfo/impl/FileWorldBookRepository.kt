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

        val target = File(dir, "$name.json")
        target.writeText(json, Charsets.UTF_8)
        // 刚解析过，顺手把条目数缓存写好，省掉列表页再解析一遍
        prefs.edit()
            .putString(countKey(name), "${book.entries.size}:${target.length()}:${target.lastModified()}")
            .apply()
        return WorldBookRef(name = name, entryCount = book.entries.size, enabled = name in enabledSet())
    }

    override fun delete(name: String) {
        File(dir, "$name.json").delete()
        setEnabled(name, false)
        prefs.edit().remove(countKey(name)).apply()
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

    override fun forcedEntries(name: String): Set<Int> =
        prefs.getStringSet(forcedKey(name), emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    override fun setEntryForced(name: String, uid: Int, forced: Boolean) {
        val current = forcedEntries(name).toMutableSet()
        if (forced) current += uid else current -= uid
        prefs.edit().putStringSet(forcedKey(name), current.map { it.toString() }.toSet()).apply()
    }

    override fun saveEntryOverrides(name: String, disabled: Set<Int>, forcedOn: Set<Int>) {
        prefs.edit()
            .putStringSet(entryKey(name), disabled.map { it.toString() }.toSet())
            .putStringSet(forcedKey(name), forcedOn.map { it.toString() }.toSet())
            .apply()
    }

    private fun entryKey(name: String): String = "disabled\u0000$name"

    private fun forcedKey(name: String): String = "forced\u0000$name"

    private fun files(): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.sortedBy { it.name }

    private fun nameOf(file: File): String = file.name.removeSuffix(".json")

    /**
     * 条目数**带缓存**：设置页只要一个"N 条"，没必要每次把整本书的 JSON 重新解析一遍
     * （真机日志里一本书数条目就要 163ms，还是启动路径）。缓存键带字节数与 mtime，文件一改就失效。
     */
    private fun countEntries(name: String, file: File): Int {
        val size = file.length()
        val mtime = file.lastModified()
        val cached = prefs.getString(countKey(name), null)
        if (cached != null) {
            val parts = cached.split(':')
            if (parts.size == 3 &&
                parts[1].toLongOrNull() == size &&
                parts[2].toLongOrNull() == mtime
            ) {
                parts[0].toIntOrNull()?.let { return it }
            }
        }
        val count = runCatching {
            parseWorldBook(name, file.readText(Charsets.UTF_8)).entries.size
        }.getOrDefault(0)
        prefs.edit().putString(countKey(name), "$count:$size:$mtime").apply()
        return count
    }

    private fun countKey(name: String): String = "count\u0000$name"

    private fun enabledSet(): Set<String> = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()

    /** 文件名要能安全落盘：去掉路径分隔符之类的字符，其余（含中文）保留。 */
    private fun sanitize(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(64)

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
