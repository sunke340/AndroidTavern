package app.tavern.data.presets

import app.tavern.core.preset.Preset
import app.tavern.core.preset.PromptOrderEntry

/**
 * 预设文件（原版 SillyTavern 的 `.json`）。
 *
 * 存法跟世界书一样：**整存整取的文档**，一个预设一个 `.json` 文件（`filesDir/presets/<名字>.json`），
 * 不建表 —— 用户会拿 PC 版的预设直接拖进来，保持原文件才能原样导回去（ADR 0003 说的是聊天用 SQLite）。
 *
 * "当前用哪一份"和"我改过的发送顺序"是**这台设备上的状态**，不属于预设文件，放 SharedPreferences。
 */
public class PresetRef(
    public val name: String,
    /** `prompt_order` 里的条数（= 这份预设能控制多少段提示词）。 */
    public val promptCount: Int,
    /** 其中关掉的条数；0 = 全开。 */
    public val disabledCount: Int,
)

public interface PresetRepository {
    /** 已导入的预设，按名字排序。 */
    public fun list(): List<PresetRef>

    /**
     * 导入一份预设。`fileName` 是文件选择器里看到的名字（用来起预设名）。
     * 同名覆盖（和原版一致）。
     */
    public fun import(fileName: String, json: String): PresetRef

    public fun delete(name: String)

    /** 读出来解析好；文件没了 / 坏了返回 null。 */
    public fun load(name: String): Preset?

    /** 当前生效的预设名；null = 内置默认预设。 */
    public fun activeName(): String?

    public fun setActive(name: String?)

    /**
     * 用户在 App 里拖动/开关之后的结果；null = 用预设文件里的原顺序。
     *
     * 单独存的原因：预设文件要保持原样（能导回 PC 版），而"我想把 Jailbreak 放到最后"
     * 是本机的偏好。删预设时一起清掉。
     */
    public fun orderOverride(name: String): List<PromptOrderEntry>?

    public fun setOrderOverride(name: String, order: List<PromptOrderEntry>?)
}
