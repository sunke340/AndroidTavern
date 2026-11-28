package app.tavern

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    /** 这台机器到底能不能识别：有服务或能解析到界面，任一条成立即可。 */
    fun available(context: Context): Boolean = serviceCount(context) > 0 || intentCount(context) > 0

    /**
     * 背后到底是哪个识别服务（包名）。
     *
     * 2026-11-27 真机 `ERROR_SERVER_DISCONNECTED`：不写包名就分不清是"系统的哪个引擎"在报错，
     * 也就判断不了"是引擎要求联网/登录"还是"我们用法不对"。
     */
    fun servicePackages(context: Context): List<String> = runCatching {
        context.packageManager
            .queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    /** 有没有**机内**（离线）识别。API 31+ 才有；有它就不用连服务端。 */
    fun onDeviceAvailable(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 31 && runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false)

    fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    /**
     * 判一次 + 留一行日志，返回能不能用。**每次进聊天页判一次**就够：系统服务不会在
     * 一次会话里冒出来，而这条日志正是"用户说不能用"时唯一能区分原因的证据。
     */
    fun check(context: Context): Boolean {
        val service = serviceCount(context)
        val intent = intentCount(context)
        val ok = available(context)
        AppLog.i(
            "ui",
            "语音识别 可用=$ok 服务=$service 意图=$intent 机内=${onDeviceAvailable(context)} " +
                "引擎=${servicePackages(context).joinToString(",").ifEmpty { "无" }}",
        )
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
/**
 * 一次识别会话：拿着识别器 + 那只看门狗，`stop()` 一次收干净。调用方不用管线程和计时器。
 */
internal class VoiceSession internal constructor(private val stopAction: () -> Unit) {
    fun stop() = stopAction()
}

internal object VoiceListen {
    /**
     * 建完识别器**延后一拍**再 `startListening`。
     *
     * 为什么（2026-11-28 真机）：`createSpeechRecognizer` 只保证"对象建好了"，服务绑定是异步的；
     * AOSP 里 `startListening()` 在没连上时走 `checkOpenConnection()` 失败 —— **只写 logcat，
     * 不抛异常、不回调一句**。于是同一帧里 create + start 就等于什么都没发生：不报错、不出字。
     * 那两次真机日志正是这个形态（`开始听` 之后 5.9s / 11.5s 内零回调）。150ms 用户感觉不到，
     * 但足够服务绑上。
     */
    private const val START_DELAY_MS = 150L

    /**
     * 这么久**一个回调都没有**就当作服务没响应，放弃。
     *
     * 为什么不是干等：引擎自己的失败要 15 秒（v186 真机：15.7s 才报 11），对着空气等 15 秒
     * 比报错还难受。4 秒足够任何健康的绑定 + `onReadyForSpeech`。
     */
    private const val SILENT_TIMEOUT_MS = 4000L

    /**
     * 开始听。返回会话（调用方存着，结束/离开时 [VoiceSession.stop]）；这台机器没有识别服务时返回 null。
     *
     * 启动阶段失败（`startListening` 抛错）走 `onError("识别器启动失败")`，不返回 null ——
     * 因为真正的 start 发生在 [START_DELAY_MS] 之后，那时已经没法改返回值了。
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
    ): VoiceSession? {
        if (!VoiceInput.available(context)) return null
        // 有机内（离线）识别就优先用它：不用连服务端，也就不会 `ERROR_SERVER_DISCONNECTED`
        //（2026-11-27 真机就是这条：允许权限后引擎在听，15 秒后服务端连接断开）
        val onDevice = VoiceInput.onDeviceAvailable(context)
        val recognizer = runCatching {
            if (onDevice && android.os.Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull() ?: return null
        val handler = Handler(Looper.getMainLooper())
        val t0 = SystemClock.elapsedRealtime()
        fun since() = SystemClock.elapsedRealtime() - t0
        var closed = false
        var anyCallback = false
        var partialLogged = false
        var rmsPeak = 0f

        val watchdog = Runnable {
            if (!closed) {
                closed = true
                AppLog.w("ui", "语音识别：${SILENT_TIMEOUT_MS}ms 没有任何回调，放弃")
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
                onError("识别服务没响应")
            }
        }
        // 第一个回调就撤销看门狗：说明服务真的在跑，后面交给引擎自己的超时
        fun alive(what: String) {
            if (!anyCallback) {
                anyCallback = true
                handler.removeCallbacks(watchdog)
                AppLog.i("ui", "语音识别：首个回调 $what（${since()}ms）")
            }
        }
        val session = VoiceSession {
            closed = true
            handler.removeCallbacks(watchdog)
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                alive("服务就绪")
            }

            // 这一句是"麦克风真的收到了声音"的判据（和"服务就绪"分开记）
            override fun onBeginningOfSpeech() {
                alive("听到声音")
            }

            override fun onRmsChanged(rmsdB: Float) {
                alive("音量")
                if (rmsdB > rmsPeak) rmsPeak = rmsdB
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                alive("说完了（${since()}ms）")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                alive("事件 $eventType")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (closed) return
                val text = partialResults?.text() ?: return
                if (text.isEmpty()) return
                alive("中间结果")
                // 第一条中间结果说明"麦克风收到了、引擎也认了" —— 排障时这句最值钱
                if (!partialLogged) {
                    partialLogged = true
                    AppLog.i("ui", "语音识别：首个中间结果 ${text.length} 字")
                }
                onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                if (closed) return
                val text = results?.text().orEmpty()
                AppLog.i(
                    "ui",
                    "语音识别：结果 ${text.length} 字（${since()}ms，" +
                        "备选 ${results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.size ?: 0} 条，" +
                        "音量峰值 ${rmsPeak.toInt()}）",
                )
                onFinal(text)
            }

            override fun onError(error: Int) {
                if (closed) return
                AppLog.w(
                    "ui",
                    "语音识别：错误 ${errorName(error)}（${since()}ms，回调见过=${anyCallback}，" +
                        "音量峰值 ${rmsPeak.toInt()}）",
                )
                onError(errorName(error))
            }
        })

        val intent = VoiceInput.recognizeIntent()
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "说话…")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        // 只有真要机内识别时才提这个要求：键存在本身就是一种要求，服务端引擎可能据此改走离线
        if (onDevice) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

        AppLog.i(
            "ui",
            "语音识别：开始听（${if (onDevice) "机内" else "服务端"}，延后 ${START_DELAY_MS}ms 等绑定）",
        )
        handler.postDelayed(
            {
                if (!closed) {
                    val ok = runCatching { recognizer.startListening(intent) }.isSuccess
                    if (!ok) {
                        closed = true
                        handler.removeCallbacks(watchdog)
                        AppLog.w("ui", "语音识别：startListening 抛错（服务没绑上？）")
                        runCatching { recognizer.destroy() }
                        onError("识别器启动失败")
                    }
                }
            },
            START_DELAY_MS,
        )
        handler.postDelayed(watchdog, START_DELAY_MS + SILENT_TIMEOUT_MS)
        return session
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
        // 11+ 的：服务端断开 / 语言不支持 / 语言不可用 / 不能检查支持
        11 -> "识别服务断开"
        12 -> "不支持这种语言"
        13 -> "语言包没装"
        14 -> "无法检查语言支持"
        else -> "未知错误 $error"
    }
}
