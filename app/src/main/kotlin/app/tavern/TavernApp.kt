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
        AppLog.i("app", "启动 build=${BuildConfig.BUILD_ID.take(7)}")

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
}
