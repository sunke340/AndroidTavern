package app.tavern

import android.app.Application
import app.tavern.data.log.AppLog

/**
 * 应用入口：装日志 + 崩溃兜底。
 *
 * 真机上的偶发问题（闪烁、崩溃、请求失败）在这里留下痕迹，用户能一键导出。
 * 注意：`Application.onCreate` 里不做重活，这里只做两件很轻的事。
 */
class TavernApp : Application() {

    override fun onCreate() {
        super.onCreate()

        AppLog.init(
            context = this,
            header = "build=${BuildConfig.BUILD_ID} version=${BuildConfig.VERSION_NAME}" +
                "(${BuildConfig.VERSION_CODE})",
        )
        // sdk 很关键：背景模糊（RenderEffect）要 API 31+，低于这个版本玻璃只会退化成半透明
        AppLog.i(
            "app",
            "启动 build=${BuildConfig.BUILD_ID.take(7)} sdk=${android.os.Build.VERSION.SDK_INT}" +
                " ${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}",
        )
        logAnimationScale()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 崩溃前把堆栈落盘，这样用户重启后还能导出
            AppLog.e("crash", "uncaught in ${thread.name}", error)
            error.stackTrace.take(12).forEach { frame ->
                AppLog.e("crash", "  at $frame")
            }
            defaultHandler?.uncaughtException(thread, error)
        }
    }

    /**
     * 记一行系统的"动画时长比例"。
     *
     * 用户报"转场还是不对"时，这是最容易被忽略的变量：开发者选项里关掉动画、或某些省电模式把
     * `animator_duration_scale` 设成 0，Compose 的所有过渡都会瞬间完成 —— 看起来就像"没动画/跳一下"，
     * 而代码怎么改都没用。日志里先有这个值，才能排除掉这一层。
     */
    private fun logAnimationScale() {
        val resolver = contentResolver
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(
                resolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        AppLog.i("ui", "系统动画比例=$scale${if (scale == 0f) "（动画被关掉了，改代码没用）" else ""}")
    }
}
