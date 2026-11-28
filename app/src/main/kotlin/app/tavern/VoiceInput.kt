package app.tavern

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.tavern.data.log.AppLog
import kotlin.math.abs
import kotlin.math.sqrt

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

    /**
     * 系统配置里"默认语音识别服务"是哪个组件（读不到就返回空串）。
     *
     * 为什么要它：`引擎=com.vivo.ai.copilot` 只说明"这个包里有识别服务"，不说明
     * **框架实际会去找哪一个**。两者不一致时现象就是"点了没反应"—— 把默认组件写进日志，
     * 才能判断是不是找错了对象（2026-11-28 判读"点了没有任何回调"时缺的就是这一格）。
     */
    fun defaultService(context: Context): String = runCatching {
        android.provider.Settings.Secure
            .getString(context.contentResolver, "voice_recognition_service")
    }.getOrNull().orEmpty()

    /**
     * 识别服务**自己声明**了什么：是否导出、要不要权限、是否启用。
     *
     * 为什么要它（2026-11-28）：判"引擎到底开不开放给第三方"不能只凭行为推 —— 行为可能是它
     * 云端挂了。声明是硬事实：`导出=false` 或签名级 `权限=` 就意味着第三方结构上用不了，
     * 反过来"导出=true 且无权限"就说明通了、问题在它服务端。
     */
    @Suppress("DEPRECATION")
    fun serviceDeclaration(context: Context): String = runCatching {
        val comp = android.content.ComponentName.unflattenFromString(defaultService(context))
            ?: return@runCatching "无默认组件"
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getServiceInfo(
                comp,
                android.content.pm.PackageManager.ComponentInfoFlags.of(0),
            )
        } else {
            context.packageManager.getServiceInfo(comp, 0)
        }
        "导出=${info.exported} 启用=${info.enabled} 权限=${info.permission ?: "无"}"
    }.getOrElse { "读不到（${it::class.simpleName}）" }

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
                "引擎=${servicePackages(context).joinToString(",").ifEmpty { "无" }} " +
                "默认=${defaultService(context).ifEmpty { "读不到" }} " +
                "声明=${serviceDeclaration(context)}",
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
    // ⚠️ 别再补发 startListening（apk-m231 试过，是坏主意）：第 2 发会立刻回 `客户端错误`，
    // 而 AOSP/公开报告里的原话是 "concurrent startListening received - ignoring this call" ——
    // 那是**我们自己的第二发**打出来的，不是引擎拒绝。要判"发出去没有"，靠服务声明与引擎回调，
    // 不靠重发（2026-11-28 真机，见 docs/dev/verification/2026-11-27-M35.md v191）。

    /**
     * 这么久**一个回调都没有**才放弃。
     *
     * 为什么是 25 秒而不是 4 秒：4 秒那次（`apk-m229`）把窗口卡在了引擎开口**之前**，
     * 等于自己把它掐了 —— 判不出"它不说话"还是"它在等握手"。v186 真机里那个
     * `ERROR_SERVER_DISCONNECTED` 是**开始听 15.7 秒后**才到的，所以窗口必须比它长，
     * 才能看到这个 25 秒里到底发生了哪一种：中间结果 / 没听到声音 / 识别服务断开 / 仍然零回调。
     * 四种结局各自指向不同结论，见 docs/dev/verification/2026-11-27-M35.md 的 v190 一节。
     */
    private const val SILENT_TIMEOUT_MS = 25_000L

    /**
     * 开始听。返回会话（调用方存着，结束/离开时 [VoiceSession.stop]）；这台机器没有识别服务时返回 null。
     *
     * 启动阶段失败（`startListening` 抛错）走 `onError("识别器启动失败")`，不返回 null ——
     * 因为真正的 start 发生在麦克风自检之后（工作线程上），那时已经没法改返回值了。
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
                AppLog.w("ui", "语音识别：${SILENT_TIMEOUT_MS / 1000} 秒没有任何回调（连 onReadyForSpeech 都没来），放弃")
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
            "语音识别：开始听（${if (onDevice) "机内" else "服务端"}；先自检麦克风，再发一次 startListening）",
        )

        // 只发一次（见上面那条警告）：补发会把还在等握手的会话搅掉。
        fun attemptStart() {
            if (closed || anyCallback) return
            val sent = runCatching { recognizer.startListening(intent) }.isSuccess
            AppLog.i("ui", "语音识别：已发出 startListening（${since()}ms）${if (sent) "" else "，抛错"}")
            if (!sent) {
                closed = true
                handler.removeCallbacks(watchdog)
                runCatching { recognizer.destroy() }
                onError("识别器启动失败")
            }
        }

        // 先自己录一小段（证"我们这条线能收音"），录完再开始听 —— 免得两边抢麦克风。
        // 为什么要有它：万一引擎死活不说话，得先排除"我们连录音都录不到"这一层，
        // 否则后面选方案（比如内置离线引擎）会押在错误的假设上。
        val probe = Thread({
            AppLog.i("ui", "麦克风自检 ${selfMicProbe()}")
            handler.post {
                if (!closed) {
                    handler.postDelayed(watchdog, SILENT_TIMEOUT_MS)
                    attemptStart()
                }
            }
        }, "mic-probe")
        probe.isDaemon = true
        probe.start()
        return session
    }

    /**
     * 自己录一小段，量峰值与均方根，用来把"引擎不说话"和"我们录不到"分开。
     *
     * 只读 1.2 秒就释放；**必须在工作线程调**（会阻塞）。
     * 返回值直接当日志正文用（不含正文内容，只有数字）。
     */
    private fun selfMicProbe(): String {
        val rate = 16_000
        val min = runCatching {
            AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(-1)
        if (min <= 0) return "不可用（getMinBufferSize=$min）"
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2,
            )
        }.getOrNull() ?: return "不可用（AudioRecord 构造失败）"
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return "不可用（未初始化）"
        }
        var peak = 0
        var sumSq = 0.0
        var count = 0
        val started = SystemClock.elapsedRealtime()
        runCatching {
            recorder.startRecording()
            val buffer = ShortArray(min)
            val until = started + 1_200
            while (SystemClock.elapsedRealtime() < until) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    val v = abs(buffer[i].toInt())
                    if (v > peak) peak = v
                    sumSq += v.toDouble() * v
                }
                count += read
            }
            recorder.stop()
        }.onFailure { return "读取失败（${it::class.simpleName}）" }
        runCatching { recorder.release() }
        val rms = if (count > 0) sqrt(sumSq / count).toInt() else 0
        return "可以录：${count} 采样/${SystemClock.elapsedRealtime() - started}ms 峰值=$peak 均方根=$rms"
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
