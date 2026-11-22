package app.tavern.data.regex

import app.tavern.core.regex.RegexScript

/**
 * 正则脚本（设置→正则）。
 *
 * 存法是"一坨原版脚本数组 JSON"：导入进来什么样、写出去还是什么样，
 * 用户在 PC 版 ST 里攒的脚本可以直接拿过来、也能拿回去。
 */
public interface RegexRepository {
    public fun scripts(): List<RegexScript>

    /** 导入：按脚本名合并（同名的替换），返回合并后总数。 */
    public fun import(json: String): Int

    public fun setDisabled(name: String, disabled: Boolean)
}
