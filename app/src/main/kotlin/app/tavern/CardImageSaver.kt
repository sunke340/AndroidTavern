package app.tavern

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.tavern.data.log.AppLog

/**
 * 把卡图存进系统相册（P2-4 的「图片长按保存」那一半，这里做成 ⋮ 菜单的一项）。
 *
 * 为什么走 MediaStore 而不是自己挑目录：不需要存储权限（API 29+），文件也立刻出现在相册里，
 * 别的应用能直接用。API 26–28 仍要 `WRITE_EXTERNAL_STORAGE`，由调用方先申请（清单里限了 maxSdkVersion=28）。
 */
object CardImageSaver {
    /** 相册里的子目录名（用户在「图片」里能按这个找到）。 */
    private const val ALBUM = "AndroidTavern"

    /**
     * @param fileName 不带扩展名；会补 `-yyyyMMdd-HHmmss.png` 防重名
     * @return 成功时那条 MediaStore 的 Uri
     */
    fun save(context: Context, png: ByteArray, fileName: String): Result<Uri> = runCatching {
        val resolver = context.contentResolver
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val displayName = "${fileName.ifBlank { "card" }}-$stamp.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("相册里建不了条目")
        try {
            resolver.openOutputStream(uri)?.use { it.write(png) } ?: error("打不开输出流")
        } catch (error: Exception) {
            // 半成品不许留在相册里
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        AppLog.i("card", "卡图已存相册 uri=${uri.lastPathSegment} 字节=${png.size}")
        uri
    }
}
