package app.tavern

import android.content.Intent
import android.net.Uri

/**
 * 从别的应用「分享」进来的文件（P2-4 的收卡那一半）。
 *
 * 为什么用一个小对象而不是直接把 Uri 塞进 Activity → ViewModel：分享可能带着 Activity 重建进来
 * （`onCreate` 和 `onNewIntent` 两条路），而 ViewModel 在 `setContent` 之前就拿不到。
 * 这里只做**一次性交接**：Activity 放进来，MainScreen 组合好后立刻取走，取一次就清。
 */
object SharedIntake {
    private var pending: Uri? = null

    /** Activity 侧调用：只认 `ACTION_SEND` 带一个 stream 的那种（分享卡图 / .charx）。 */
    fun fromIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val type = intent.type.orEmpty()
        // 卡：PNG 图或 charx（zip）。别的类型（文本、链接）不接。
        if (type.startsWith("image/") || type == "application/zip" || type == "application/octet-stream") {
            pending = uri
            app.tavern.data.log.AppLog.i("card", "收到分享 uri=${uri.scheme} type=$type")
        }
    }

    /** MainScreen 侧调用：取走待导入的分享（取到就清空，避免重复导入）。 */
    fun take(): Uri? {
        val uri = pending
        pending = null
        return uri
    }
}
