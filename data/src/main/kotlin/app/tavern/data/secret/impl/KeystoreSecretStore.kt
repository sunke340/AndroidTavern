package app.tavern.data.secret.impl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.tavern.data.secret.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-GCM 密钥加密机密值，密文存 SharedPreferences。
 *
 * 几条硬约束：
 * - 明文**不落盘**（只进内存和 Keystore 加密结果），也不进日志。
 * - 密钥本身在 Keystore 里，应用卸载即销毁；换机/清除凭据后解不开 → 当没存过，让用户重填。
 * - 解密失败**不抛异常**：密钥失效不该让 App 起不来，退回"没填"即可。
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun put(name: String, value: String) {
        if (value.isEmpty()) {
            remove(name)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // IV 必须与密文一起存（GCM 的 IV 由 Keystore 每次生成）
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, payload).apply()
    }

    override fun get(name: String): String? {
        val payload = prefs.getString(name, null) ?: return null
        val parts = payload.split(':', limit = 2)
        if (parts.size != 2) return null

        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tavern.settings.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS = "tavern.secrets"
    }
}
