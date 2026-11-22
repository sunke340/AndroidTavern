package app.tavern

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 头像缓存：卡 PNG 的缩略图。16 张足够撑满一屏两列网格。 */
private val avatarCache = LruCache<String, ImageBitmap>(16)

/**
 * 角色卡头像——就是卡本身那张 PNG，解码缩到 ~320px。
 *
 * 不引 Coil：只读本地文件、只要缩略图，`BitmapFactory` + `inSampleSize` 就够；
 * 少一个依赖 = 少一次 CI 失败面（这台机器编不了 Android 模块，只能靠 CI）。
 */
@Composable
fun CardAvatar(cardId: String, fallbackText: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { AppGraph.cards(context) }

    val bitmap by produceState(initialValue = avatarCache.get(cardId), cardId) {
        if (value != null) return@produceState
        val decoded = withContext(Dispatchers.IO) {
            avatarCache.get(cardId) ?: run {
                val bytes = repository.load(cardId) ?: return@run null
                decodeSampled(bytes, target = 320)?.asImageBitmap()?.also { avatarCache.put(cardId, it) }
            }
        }
        if (decoded != null) value = decoded
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        // 还没解出来 / 解不出来：给个占位，别让网格塌成空白
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(fallbackText.take(1), style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
