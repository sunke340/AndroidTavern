package app.tavern.data.secret

/**
 * 机密存储（目前只有 API Key）。
 *
 * 纪律（`data/AGENTS.md`）：**明文不落盘、不进日志、不进 fixture**。
 * 实现见 [app.tavern.data.secret.impl.KeystoreSecretStore]：密文存 SharedPreferences，
 * 密钥在 Android Keystore 里，不出应用进程。
 */
interface SecretStore {
    fun put(name: String, value: String)

    fun get(name: String): String?

    fun remove(name: String)
}
