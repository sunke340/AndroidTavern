package app.tavern.data.worldinfo

import app.tavern.core.card.WorldBookFile

/**
 * 全局世界书（设置里那页）。条目格式见 `:core:card-format` 的 `parseWorldBook`。
 *
 * 与"卡内嵌书"分开：卡内嵌书跟着卡走，勾选存在 `SettingsRepository`；
 * 这里的书是独立的 `.json` 文件，启用/停用是全局的，缺省**停用**（和原版不同：原版导入后要手动选）。
 * 为什么默认停用：一本书几十上百条，导入完立刻全局生效很容易把别人的卡带偏——先只登记，由人决定开不开。
 */
public class WorldBookRef(
    public val name: String,
    public val entryCount: Int,
    public val enabled: Boolean,
)

public interface WorldBookRepository {
    /** 已导入的书，按名字排序。 */
    public fun list(): List<WorldBookRef>

    /**
     * 导入一本书。`fileName` 是用户在文件选择器里看到的名字（用来起书名字）。
     * 同名会覆盖——原版导入同名书也是覆盖。
     */
    public fun import(fileName: String, json: String): WorldBookRef

    public fun delete(name: String)

    /** 读出来解析好；文件没了 / 坏了返回 null。 */
    public fun load(name: String): WorldBookFile?

    public fun setEnabled(name: String, enabled: Boolean)

    /** 这本书里被手动关掉的条目 uid（覆盖文件里的 `disable`）。 */
    public fun disabledEntries(name: String): Set<Int>

    /** 逐条开关：覆盖文件里的 `disable`，不改文件本身（书还能原样导回 PC 版）。 */
    public fun setEntryDisabled(name: String, uid: Int, disabled: Boolean)
}
