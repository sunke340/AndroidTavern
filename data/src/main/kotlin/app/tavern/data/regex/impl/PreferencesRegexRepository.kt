package app.tavern.data.regex.impl

import android.content.Context
import app.tavern.core.regex.RegexScript
import app.tavern.core.regex.parseRegexScripts
import app.tavern.core.regex.writeRegexScripts
import app.tavern.data.regex.RegexRepository

/**
 * 脚本数组整个放在 SharedPreferences 的一个字符串里。
 *
 * 为什么不落文件：它就是几十 KB 的配置，导入导出都是整存整取，
 * 放 prefs 省掉"文件不存在 / 权限 / 原子写"三类状态。
 */
public class PreferencesRegexRepository(context: Context) : RegexRepository {

    private val prefs = context.getSharedPreferences("regex_scripts", Context.MODE_PRIVATE)

    override fun scripts(): List<RegexScript> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return parseRegexScripts(raw)
    }

    override fun import(json: String): Int {
        val incoming = parseRegexScripts(json)
        require(incoming.isNotEmpty()) { "文件里没有正则脚本" }

        val merged = LinkedHashMap<String, RegexScript>()
        scripts().forEach { merged[keyOf(it)] = it }
        incoming.forEach { merged[keyOf(it)] = it }
        val result = merged.values.toList()
        save(result)
        return result.size
    }

    override fun setDisabled(name: String, disabled: Boolean) {
        val current = scripts()
        val next = current.map { if (it.name == name) it.copyWithDisabled(disabled) else it }
        save(next)
    }

    private fun save(scripts: List<RegexScript>) {
        prefs.edit().putString(KEY, writeRegexScripts(scripts)).apply()
    }

    private fun keyOf(script: RegexScript): String = script.name.ifBlank { script.findRegex }

    private companion object {
        const val KEY = "scripts"
    }
}

/** 只改一个字段：`RegexScript` 的字段全是 val，所以重建一个。 */
private fun RegexScript.copyWithDisabled(disabled: Boolean): RegexScript = RegexScript(
    name = name,
    findRegex = findRegex,
    replaceString = replaceString,
    trimStrings = trimStrings,
    placement = placement,
    disabled = disabled,
    markdownOnly = markdownOnly,
    promptOnly = promptOnly,
    runOnEdit = runOnEdit,
    substituteRegex = substituteRegex,
    minDepth = minDepth,
    maxDepth = maxDepth,
)
