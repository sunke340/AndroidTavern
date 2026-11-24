package app.tavern

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 会话列表的分组：今天 / 更早。 */
enum class SessionDay(val label: String) {
    Today("今天"),
    Earlier("更早"),
}

/**
 * 会话属于哪一组。
 *
 * 只分两组是刻意的：昨天、三天前、上周 的差别交给行尾的时间标签去说，
 * 分得再细（今天/昨天/本周/本月）标题就会比内容还多。顺序仍由 `updated_at` 倒序保证。
 */
fun sessionDay(epochMillis: Long, now: Long = System.currentTimeMillis()): SessionDay =
    if (daysAgo(epochMillis, now) <= 0L) SessionDay.Today else SessionDay.Earlier

/**
 * 行尾的时间标签（右对齐、等宽数字，宽 44dp）。
 *
 * - 今天 → `14:20`（日期已经在分组标题里，不重复）
 * - 昨天 → `昨天`
 * - 一周内 → `周二`
 * - 同年 → `11-18`
 * - 跨年 → `2025`
 *
 * 依据 [docs/design/motion.md] 同源的 ui-cheatsheet："超过一周就换成绝对日期"。
 * 一行放不下完整日期，所以同年省略年份；跨年的只留年份（别的年份里"几月几日"意义不大）。
 */
fun sessionTimeLabel(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone)
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), today.toLocalDate())
    return when {
        // 0 = 今天；负数 = 时钟比库里新（极少见），也按今天处理
        days <= 0L -> time.format(clockwise)
        days == 1L -> "昨天"
        days < 7L -> weekdayLabels[time.dayOfWeek.value - 1]
        time.year == today.year -> time.format(monthDayFormatter)
        else -> time.year.toString()
    }
}

/**
 * 会话行的副行：最后一条消息压成一行。
 *
 * 不在这里截字数：截断交给 `Text(maxLines = 1)`，省略号才会落在"行宽允许的位置"，
 * 不会在宽屏上留一大截空白。用户自己发的那条前面加「你：」。
 */
fun previewLine(raw: String?, fromUser: Boolean): String? {
    val text = raw
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.replace(whitespaceRun, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return if (fromUser) "你：$text" else text
}

private fun daysAgo(epochMillis: Long, now: Long): Long {
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(then, today)
}

private val clockwise: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US)

private val monthDayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd", Locale.US)

private val whitespaceRun = Regex("\\s+")

/** `dayOfWeek.value` 是 1..7（周一到周日）。 */
private val weekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
