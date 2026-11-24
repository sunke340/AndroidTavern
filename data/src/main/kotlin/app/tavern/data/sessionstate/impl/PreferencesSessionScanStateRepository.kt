package app.tavern.data.sessionstate.impl

import android.content.Context
import app.tavern.data.sessionstate.SessionScanStateRepository

/** 一个会话一条字符串；空串等于"没有在生效的计时状态"，直接删 key。 */
class PreferencesSessionScanStateRepository(context: Context) : SessionScanStateRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(sessionId: String): String? = prefs.getString(sessionId, null)

    override fun save(sessionId: String, text: String) {
        prefs.edit().apply {
            if (text.isBlank()) remove(sessionId) else putString(sessionId, text)
        }.apply()
    }

    override fun clear(sessionId: String) {
        prefs.edit().remove(sessionId).apply()
    }

    private companion object {
        const val PREFS = "session_scan_state"
    }
}
