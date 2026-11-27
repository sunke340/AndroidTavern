package app.tavern

import android.content.Context
import android.content.Intent
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
