package app.tavern.data.profiles

import app.tavern.core.model.ModelProfile

/**
 * 模型档案（多套连接配置）。
 *
 * 为什么不复用 `SettingsRepository`：那是"单值偏好"，而档案是**一组会增删的实体**，
 * 生命周期和 Key 绑定（每个档案一把 Key）。混在一起以后加"每档案独立采样参数"会更乱。
 *
 * 纪律：Key 只走 [apiKey] / [saveApiKey]，明文不落盘、不进日志。
 */
interface ProfileRepository {

    /** 按插入顺序返回（不排序：用户自己建的顺序就是顺序）。 */
    fun list(): List<ModelProfile>

    fun find(id: String): ModelProfile?

    /** 新增或按 id 覆盖。`isDefault` 只在这里写库，切换默认走 [setDefault]。 */
    fun save(profile: ModelProfile)

    fun delete(id: String)

    /** 把这条设为唯一默认（其它条的 `is_default` 清掉）。 */
    fun setDefault(id: String)

    /** 解密后的 Key；没有就返回空串（不要返回 null，调用方到处判空很啰嗦）。 */
    fun apiKey(profileId: String): String

    /** 空串 = 删除该档案的 Key。 */
    fun saveApiKey(profileId: String, key: String)

    /**
     * 首次运行 / 从老版本升级上来时调用：表里还没有档案，就用老的"单套连接配置"种一条默认档案，
     * 并把老的 Key **复制**一份到新档案名下（旧值保留，出问题能回退）。
     *
     * 幂等：已经有档案就什么都不做。
     *
     * @return 是否真的种了（用于日志）。
     */
    fun ensureSeeded(legacyBaseUrl: String, legacyModel: String, legacyApiKey: String): Boolean
}
