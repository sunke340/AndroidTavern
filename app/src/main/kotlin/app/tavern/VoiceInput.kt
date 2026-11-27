package app.tavern

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.tavern.data.log.AppLog

/**
 * 这台设备到底有没有可用的语音识别（P2-3 的语音输入）。
 *
 * 为什么要有它（2026-11-27 真机）：v183 点「语音输入」只弹一句"这台设备没有语音识别"，
 * 而那句来自 `runCatching { launch }.isSuccess` —— 它**分不清**三种情况：
 *
 * 1. 系统真没有识别器（不少国行 ROM 没有 Google 语音服务）；
 * 2. 有，但我们被 Android 11 的**包可见性**挡住，`startActivity` 解析不到（后来补了
 *    Manifest 的 `<queries>`，见那边的注释）；
 * 3. 有，但只实现了 `RecognitionService`，没有处理 `ACTION_RECOGNIZE_SPEECH` 的 Activity。
 *
 * 所以这里把两条判据分别算出来、各打一行日志：
 * - `服务`：[SpeechRecognizer.isRecognitionAvailable]（有 RecognitionService 实现）；
 * - `意图`：`ACTION_RECOGNIZE_SPEECH` 能解析出几个 Activity。
 *
 * 任一条为真就算"可以用"：有的 ROM 只开放系统语音助手那条路。
 */
internal object VoiceInput {
    /** 有 RecognitionService 实现就是 1，否则 0（这个接口只回答有没有）。 */
    fun serviceCount(context: Context): Int = runCatching {
        if (SpeechRecognizer.isRecognitionAvailable(context)) 1 else 0
    }.getOrDefault(0)

    /** `ACTION_RECOGNIZE_SPEECH` 能解析出几个 Activity。 */
    fun intentCount(context: Context): Int = runCatching {
        context.packageManager.queryIntentActivities(recognizeIntent(), 0).size
    }.getOrDefault(0)

    fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    /**
     * 判一次 + 留一行日志，返回能不能用。**每次进聊天页判一次**就够：系统服务不会在
     * 一次会话里冒出来，而这条日志正是"用户说不能用"时唯一能区分原因的证据。
     */
    fun check(context: Context): Boolean {
        val service = serviceCount(context)
        val intent = intentCount(context)
        val ok = service > 0 || intent > 0
        AppLog.i("ui", "语音识别 可用=$ok 服务=$service 意图=$intent")
        return ok
    }
}

/**
 * App 内直连识别（`SpeechRecognizer`）。
 *
 * 什么时候需要它：系统没有能处理 `ACTION_RECOGNIZE_SPEECH` 的界面（真机日志 `服务=1 意图=0`），
 * 这时只能自己建会话、自己收音 —— 代价是要 `RECORD_AUDIO` 权限（走系统界面那条不用）。
 *
 * 调用方负责：先确认权限、并在离开页面时 [SpeechRecognizer.destroy]。
 * 这里只保证"拿到最终文本就回调一次"，不给 UI 加任何东西。
 */
internal object VoiceListen {
    /**
     * 开始听。返回识别器（调用方存着，结束/离开时 destroy）；这台机器没有识别服务时返回 null。
     *
     * @param onPartial 中间结果（`EXTRA_PARTIAL_RESULTS`），用来边听边把字填进输入框
     * @param onFinal 最终结果（可能是空串：识别器没听清）
     * @param onError 错误码的可读描述（同时写日志用）
     */
    fun start(
        context: Context,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ): SpeechRecognizer? {
        if (!VoiceInput.available(context)) return null
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: return null
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.text() ?: return
                if (text.isNotEmpty()) onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                onFinal(results?.text().orEmpty())
            }

            override fun onError(error: Int) {
                onError(errorName(error))
            }
        })
        val intent = VoiceInput.recognizeIntent()
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "说话…")
        val ok = runCatching { recognizer.startListening(intent) }.isSuccess
        if (!ok) {
            runCatching { recognizer.destroy() }
            return null
        }
        return recognizer
    }

    private fun Bundle.text(): String =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /** 错误码 → 人话（日志里不写数字，真机排查时一眼能看懂）。 */
    fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络不可用"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "未知错误 $error"
    }
}
