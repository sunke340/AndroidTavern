package app.tavern.data.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志：内存环形缓冲 + 落盘追加。
 *
 * 为什么要有它：很多问题（偶发闪烁、请求失败、崩溃）只发生在真机上，而这台开发机
 * 没有设备、也没有 logcat。把关键事件和异常留在应用里，用户能一键导出成文本发回来。
 *
 * 三条硬约束：
 * 1. **绝不能因为记日志而崩溃**：所有写入都 try/catch 吞掉异常；
 * 2. **不能无限长**：内存 800 条、文件超过 1MB 就滚动；
 * 3. **不放敏感信息**：只记模型名、条数、状态码，绝不记 Key、消息正文。
 */
object AppLog {

    enum class Level { D, I, W, E }

    data class Entry(
        val at: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val MAX_MEMORY = 800
    private const val MAX_FILE_BYTES = 1_000_000L

    private val memory = ArrayDeque<Entry>()
    private val lock = Any()
    private var logFile: File? = null
    private var sessionHeader: String = ""
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, header: String = "") {
        synchronized(lock) {
            sessionHeader = header
            val dir = File(context.applicationContext.filesDir, "logs")
            runCatching { dir.mkdirs() }
            val file = File(dir, "app.log")
            runCatching {
                if (file.isFile && file.length() > MAX_FILE_BYTES) {
                    val rotated = File(dir, "app.old.log")
                    runCatching { rotated.delete() }
                    file.renameTo(rotated)
                }
            }
            logFile = file
        }
    }

    fun d(tag: String, message: String) = log(Level.D, tag, message)
    fun i(tag: String, message: String) = log(Level.I, tag, message)
    fun w(tag: String, message: String) = log(Level.W, tag, message)

    fun e(tag: String, message: String, error: Throwable? = null) {
        val detail = if (error == null) {
            message
        } else {
            "$message | ${error::class.simpleName}: ${error.message}"
        }
        log(Level.E, tag, detail)
    }

    private fun log(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            memory.addLast(entry)
            while (memory.size > MAX_MEMORY) memory.removeFirst()

            val file = logFile ?: return
            runCatching {
                file.appendText("${timeFormat.format(Date(entry.at))}  ${level.name}  $tag  $message\n")
            }
        }
    }

    /** 导出用：会话头 + 历史日志（内存里的，含刚写、还没落盘的）。 */
    fun exportText(buildInfo: String): String = synchronized(lock) {
        buildString {
            appendLine("# AndroidTavern 日志")
            appendLine("# build: $buildInfo")
            appendLine("# 导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            if (sessionHeader.isNotBlank()) appendLine("# $sessionHeader")
            appendLine("# 说明: 只记录事件与状态码，不含 API Key 与消息正文")
            appendLine("----")
            memory.forEach { entry ->
                appendLine(
                    "${timeFormat.format(Date(entry.at))}  ${entry.level.name}  ${entry.tag}  ${entry.message}",
                )
            }
        }
    }

    fun count(): Int = synchronized(lock) { memory.size }

    fun clear() {
        synchronized(lock) {
            memory.clear()
            runCatching { logFile?.writeText("") }
        }
    }
}
