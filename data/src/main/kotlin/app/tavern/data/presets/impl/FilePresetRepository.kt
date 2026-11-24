package app.tavern.data.presets.impl

import android.content.Context
import app.tavern.core.preset.Preset
import app.tavern.core.preset.PresetParser
import app.tavern.core.preset.PromptOrderEntry
import app.tavern.data.presets.PresetRef
import app.tavern.data.presets.PresetRepository
import java.io.File

/**
 * 预设存成原版那种 `.json` 文件（`filesDir/presets/<名字>.json`）。
 *
 * 导入时就解析一次：不是一个能解析的预设就别进目录（否则设置页会列出一份点进去就崩的文件）。
 *
 * 顺序覆盖用一行一条的纯文本存在 SharedPreferences（`identifier<TAB>0/1`）：
 * 它只有十几条、要人肉看得懂、而且不参与导出 —— 不值得为它引序列化。
 */
public class FilePresetRepository(context: Context) : PresetRepository {

    private val dir: File = File(context.filesDir, "presets").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)

    override fun list(): List<PresetRef> = files().mapNotNull { file ->
        val name = nameOf(file)
        val preset = runCatching { parse(name, file) }.getOrNull() ?: return@mapNotNull null
        val order = orderOverride(name) ?: preset.order
        PresetRef(
            name = name,
            promptCount = order.size,
            disabledCount = order.count { !it.enabled },
        )
    }

    override fun import(fileName: String, json: String): PresetRef {
        val rawName = fileName.substringAfterLast('/').removeSuffix(".json").removeSuffix(".JSON")
        val name = sanitize(rawName)
        require(name.isNotBlank()) { "预设名为空" }

        // 先解析：解析不了的文件不该进目录
        val preset = PresetParser.parse(name, json)

        File(dir, "$name.json").writeText(json, Charsets.UTF_8)
        // 换了一份同名的预设：旧的顺序覆盖作废（条数可能都对不上）
        setOrderOverride(name, null)
        val order = preset.order
        return PresetRef(
            name = name,
            promptCount = order.size,
            disabledCount = order.count { !it.enabled },
        )
    }

    override fun delete(name: String) {
        File(dir, "$name.json").delete()
        setOrderOverride(name, null)
        if (activeName() == name) setActive(null)
    }

    override fun load(name: String): Preset? {
        val file = File(dir, "$name.json")
        if (!file.isFile) return null
        return runCatching { parse(name, file) }.getOrNull()
    }

    override fun activeName(): String? = prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    override fun setActive(name: String?) {
        prefs.edit().apply {
            if (name == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, name)
        }.apply()
    }

    override fun orderOverride(name: String): List<PromptOrderEntry>? {
        val raw = prefs.getString(orderKey(name), null) ?: return null
        val entries = raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val identifier = line.substringBefore('\t').takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PromptOrderEntry(identifier, enabled = !line.endsWith("\t0"))
            }
            .toList()
        return entries.ifEmpty { null }
    }

    override fun setOrderOverride(name: String, order: List<PromptOrderEntry>?) {
        val key = orderKey(name)
        prefs.edit().apply {
            if (order == null) {
                remove(key)
            } else {
                putString(key, order.joinToString("\n") { "${it.identifier}\t${if (it.enabled) 1 else 0}" })
            }
        }.apply()
    }

    private fun files(): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun parse(name: String, file: File): Preset =
        PresetParser.parse(name, file.readText(Charsets.UTF_8))

    private fun nameOf(file: File): String = file.name.removeSuffix(".json")

    private fun orderKey(name: String): String = "order\u0000$name"

    private fun sanitize(raw: String): String = raw
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .take(64)

    private companion object {
        const val KEY_ACTIVE = "active"
    }
}
