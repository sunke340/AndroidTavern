package app.tavern.data.profiles.impl

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.tavern.core.model.ModelProfile
import app.tavern.core.model.ModelProfiles
import app.tavern.data.chat.impl.TavernDatabase
import app.tavern.data.chat.impl.TavernDatabases
import app.tavern.data.profiles.ProfileRepository
import app.tavern.data.secret.SecretStore
import app.tavern.data.secret.impl.KeystoreSecretStore

/**
 * 原生 SQLite + Keystore 的实现。
 *
 * 两条不变式：
 * 1. **有且只有一个默认档案**（[ModelProfiles.normalizeDefault] 在读取时兜底）；
 * 2. **Key 按档案 id 分开存**（[ModelProfiles.keyName]），换档案不会互相覆盖。
 */
class SqliteProfileRepository(
    context: Context,
    private val secrets: SecretStore = KeystoreSecretStore(context),
) : ProfileRepository {

    private val helper = TavernDatabases.of(context)
    private val table = TavernDatabase.TABLE_PROFILES

    override fun list(): List<ModelProfile> {
        val sql = "SELECT id, name, base_url, model, headers, is_default " +
            "FROM $table ORDER BY rowid"
        return helper.readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ModelProfile(
                            id = cursor.getString(0),
                            name = cursor.getString(1),
                            baseUrl = cursor.getString(2),
                            model = cursor.getString(3),
                            headers = ModelProfiles.parseHeaders(cursor.getString(4).orEmpty()),
                            isDefault = cursor.getInt(5) != 0,
                        ),
                    )
                }
            }
        }
    }

    override fun find(id: String): ModelProfile? = list().firstOrNull { it.id == id }

    override fun save(profile: ModelProfile) {
        val values = ContentValues().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("base_url", profile.baseUrl)
            put("model", profile.model)
            put("headers", ModelProfiles.formatHeaders(profile.headers))
            put("is_default", if (profile.isDefault) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        if (profile.isDefault) setDefault(profile.id)
    }

    override fun delete(id: String) {
        helper.writableDatabase.delete(table, "id = ?", arrayOf(id))
        // Key 跟着档案走，档案没了就别留一把解不开的密文
        runCatching { secrets.remove(ModelProfiles.keyName(id)) }
        // 删的是默认档案：把第一条顶上来，别留下"没有默认"的状态
        val remaining = list()
        if (remaining.isNotEmpty() && remaining.none { it.isDefault }) setDefault(remaining.first().id)
    }

    override fun setDefault(id: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE $table SET is_default = 0")
            db.execSQL("UPDATE $table SET is_default = 1 WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun apiKey(profileId: String): String =
        runCatching { secrets.get(ModelProfiles.keyName(profileId)) }.getOrNull().orEmpty()

    override fun saveApiKey(profileId: String, key: String) {
        if (key.isBlank()) {
            secrets.remove(ModelProfiles.keyName(profileId))
        } else {
            secrets.put(ModelProfiles.keyName(profileId), key)
        }
    }

    override fun ensureSeeded(
        legacyBaseUrl: String,
        legacyModel: String,
        legacyApiKey: String,
    ): Boolean {
        if (list().isNotEmpty()) return false

        val profile = ModelProfile(
            id = ModelProfiles.LEGACY_ID,
            name = ModelProfiles.FALLBACK_NAME,
            baseUrl = legacyBaseUrl.takeIf { it.isNotBlank() } ?: ModelProfiles.FALLBACK_BASE_URL,
            model = legacyModel.takeIf { it.isNotBlank() } ?: ModelProfiles.FALLBACK_MODEL,
            isDefault = true,
        )
        save(profile)
        // 只复制不删除：万一新逻辑有问题，老的 Key 还在原地
        if (legacyApiKey.isNotBlank() && apiKey(profile.id).isEmpty()) {
            saveApiKey(profile.id, legacyApiKey)
        }
        return true
    }
}
