package app.tavern

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 从别的应用「分享」进来的文件（P2-4 的收卡那一半）。
 *
 * 为什么用一个小对象而不是直接把 Uri 塞进 Activity → ViewModel：分享可能带着 Activity 重建进来
 * （`onCreate` 与 `onNewIntent` 两条路），而 ViewModel 在 `setContent` 之前还拿不到。
 *
 * 用 `StateFlow` 而不是"一次性 take()"：app 已经在前台时再分享一次，`onNewIntent` 会再给一个 Uri，
 * 而一次性交接只在第一次组合时取走一个 —— 第二次就静默丢了。
 */
object SharedIntake {
    private val _pending = MutableStateFlow<Uri?>(null)

    /** 待导入的那个 Uri；`MainScreen` 处理完必须 [clear]，否则下一次组合会重复导入。 */
    val pending: StateFlow<Uri?> = _pending

    /** Activity 侧调用：只认 `ACTION_SEND` 带一个 stream 的那种（分享卡图 / .charx）。 */
    fun fromIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val type = intent.type.orEmpty()
        // 卡：PNG 图或 charx（zip）。别的类型（文本、链接）不接。
        if (type.startsWith("image/") || type == "application/zip" || type == "application/octet-stream") {
            _pending.value = uri
            app.tavern.data.log.AppLog.i("card", "收到分享 uri=${uri.scheme} type=$type")
        }
    }

    fun clear() {
        _pending.value = null
    }
}
