package app.tavern

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.tavern.data.log.AppLog
import java.util.Locale

/**
 * 系统 TTS 朗读（P2-3 的第一半）。
 *
 * 只用系统自带的 `TextToSpeech`：不引引擎、不联网、不申请权限。
 * 生命周期挂在聊天页上（[rememberTtsSpeaker]），离场即 shutdown —— 页面在栈里被保留时
 * 也只在真正离开组合时释放。
 *
 * **只朗读正文**：传进来的是已经过显示正则的文本（和屏幕上看到的一致），
 * 不读角色名、不读 Markdown 残留（`<i>` 这类标签由调用方先剥掉）。
 */
class TtsSpeaker(context: Context) {
    /** 引擎是否可用：false = 这台机器没装任何 TTS 引擎（按了按钮就提示）。 */
    var available by mutableStateOf(false)
        private set

    /** 正在读哪一条（`uiKey`）；null = 没在读。 */
    var speakingKey by mutableStateOf<String?>(null)
        private set

    private var ready = false
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        available = ready
        if (!ready) AppLog.w("tts", "TTS 初始化失败 status=$status")
    }.also { tts ->
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                speakingKey = null
            }

            @Deprecated("老 API，仍然要覆盖")
            override fun onError(utteranceId: String?) {
                speakingKey = null
            }
        })
    }

    /** 读一条。第二次点同一条 = 停（原版也是这个语义：再点一下停掉）。 */
    fun speak(key: String, text: String) {
        if (!ready) {
            AppLog.w("tts", "TTS 还没就绪，忽略")
            return
        }
        if (speakingKey == key) {
            stop()
            return
        }
        // 语言跟系统：中文文本用中文引擎读；没装就退回默认（引擎自己会挑）
        runCatching { engine.language = Locale.getDefault() }
        engine.stop()
        speakingKey = key
        val plain = text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(i|em|b|strong)>", RegexOption.IGNORE_CASE), "")
        val result = engine.speak(plain, TextToSpeech.QUEUE_FLUSH, null, key)
        if (result != TextToSpeech.SUCCESS) {
            speakingKey = null
            AppLog.w("tts", "speak 失败 result=$result")
        }
    }

    fun stop() {
        engine.stop()
        speakingKey = null
    }

    fun shutdown() {
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
        speakingKey = null
        ready = false
        available = false
    }
}

/** 拿到一个跟着页面组合存活的朗读器。 */
@Composable
fun rememberTtsSpeaker(context: Context): TtsSpeaker {
    val speaker = remember { TtsSpeaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }
    return speaker
}
