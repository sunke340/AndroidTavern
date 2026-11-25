package app.tavern

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.tavern.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 头像缓存：卡 PNG 的缩略图。16 张足够撑满一屏两列网格。 */
private val avatarCache = LruCache<String, ImageBitmap>(16)

/** 磨砂素材缓存：极小的缩略图（宽 ~32px）。放大画回去就是"糊"，不依赖 RenderEffect。 */
private val frostCache = LruCache<String, ImageBitmap>(16)

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
    // 解码是异步的：图出来时淡入，避免网格里"啪"地跳出图片
    val alpha by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(Motion.Base, easing = Motion.Decelerate),
        label = "avatar-alpha",
    )

    // 占位块**一直在下面**，图片出来只是淡入盖上去。
    // 以前是 if/else 两支：图片那一支的 alpha 从 0 开始 → 中间有一帧是"空的"，
    // 观感就是"头像闪一下"（用户 2026-11-24 报的启动闪烁）。
    Box(modifier) {
        Box(
            modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                Text(fallbackText.take(1), style = MaterialTheme.typography.titleLarge)
            }
        }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.matchParentSize().alpha(alpha),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** 详情页卡头的大图缓存：几张就够（一屏只有一张），但尺寸要大得多。 */
private val heroCache = LruCache<String, ImageBitmap>(4)

/**
 * 详情页卡头的大图（用户 2026-11-25 第 14 条）：
 *
 * 角色卡基本都是**竖图**（2:3 / 3:4），而卡头是横的一条 —— 直接 `ContentScale.Crop`
 * 会把上下截掉一大截（200dp 高的横条里只剩图中间那一小段）。
 *
 * 所以改成"海报"排版：背景是同图的**极小块磨砂**拉满（CardFrost，一定糊，不押 RenderEffect），
 * 前景是**完整竖图**（`ContentScale.Fit`）。两边留出的糊面正好给名字垫底，整张画也都在。
 *
 * 前台按 900px 解码：卡头现在是全宽大图，再拿 320px 的网格缩略图会被放大到发虚。
 */
@Composable
fun CardHero(cardId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { AppGraph.cards(context) }
    val bitmap by produceState(initialValue = heroCache.get(cardId), cardId) {
        if (value != null) return@produceState
        val decoded = withContext(Dispatchers.IO) {
            heroCache.get(cardId) ?: run {
                val bytes = repository.load(cardId) ?: return@run null
                decodeSampled(bytes, target = 900)?.asImageBitmap()?.also { heroCache.put(cardId, it) }
            }
        }
        if (decoded != null) value = decoded
    }
    Box(modifier) {
        Box(
            modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant),
        )
        // 背景糊片：同一张卡解成 ~24px 再拉满，观感就是"糊掉的卡面"
        CardFrost(cardId = cardId, modifier = Modifier.matchParentSize())
        // 压暗一档：浅色卡面不做这一步，压在上面的白字会消失
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.34f)))
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** 一次最多预热几张：会话首屏不会同时露出更多，避免把启动拖慢。 */
private const val PRELOAD_LIMIT = 8

/**
 * 预热头像缓存（会话列表首屏用）。
 *
 * 为什么不在行里现解：`CardAvatar` 是异步解码，第一帧只能画占位块，解完再淡入 ——
 * 十几行各解各的，观感就是"进去时头像一块块闪出来"。这里在**发布会话列表之前**
 * 把这屏要用到的几张（去重、并行）解好，行一出现就是最终画面。
 *
 * 只解码，不解码不了就跳过（坏卡不该让列表出不来）。
 */
suspend fun preloadAvatars(context: Context, cardIds: Collection<String>) {
    val ids = cardIds.filter { it.isNotBlank() }.distinct().take(PRELOAD_LIMIT)
    if (ids.isEmpty()) return
    val started = SystemClock.elapsedRealtime()
    val repository = AppGraph.cards(context)
    coroutineScope {
        ids.map { id ->
            async(Dispatchers.IO) {
                if (avatarCache.get(id) != null) return@async
                runCatching {
                    val bytes = repository.load(id) ?: return@runCatching
                    decodeSampled(bytes, target = 320)?.asImageBitmap()?.also { avatarCache.put(id, it) }
                }
            }
        }.awaitAll()
    }
    AppLog.i("startup", "头像预热 ${ids.size} 张 ${SystemClock.elapsedRealtime() - started}ms")
}

/**
 * 磨砂底片：把同一张卡解成 ~32px 的小图，放大画回去就是糊的。
 *
 * 为什么不用 `GlassSurface`（全 App 的磨砂都走它）：角色卡在**内容录图层**里面，
 * 玻璃采样的是那一层 —— 会采到自己这一帧（页头胶囊"无限套娃"就是这么来的）。
 * 预先解一次小图是这里唯一既真糊、又便宜（5KB/张）、又不碰图层时序的办法。
 */
@Composable
fun CardFrost(cardId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { AppGraph.cards(context) }

    val bitmap by produceState(initialValue = frostCache.get(cardId), cardId) {
        if (value != null) return@produceState
        val decoded = withContext(Dispatchers.IO) {
            frostCache.get(cardId) ?: run {
                val bytes = repository.load(cardId) ?: return@run null
                decodeSampled(bytes, target = 24)?.asImageBitmap()?.also { frostCache.put(cardId, it) }
            }
        }
        if (decoded != null) value = decoded
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            // 卡片底部那条磨砂要的是"这张图的下半截"，所以对齐到底部再裁
            alignment = Alignment.BottomCenter,
        )
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
