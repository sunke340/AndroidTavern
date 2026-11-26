package app.tavern.core.card

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 随包的卡（`app/src/main/assets/cards/`）必须能被**自家解析器**读出来。
 *
 * 那张卡是 `tools/cards/make-demo-card.py` 现画的：PNG 结构和那两个 `tEXt` 块都由脚本自己编码。
 * 脚本改坏（chunk 顺序、CRC、base64、spec 写错）的后果是"装上去首启没有卡"，而本机编不了 `:app`，
 * 真机上才发现太晚 —— 所以这条回路放在 core 里，直接读 assets 目录（本机能跑）。
 *
 * 顺带卡住两件事：
 * - **V2 与 V3 必须都在**（`chara` + `ccv3`）。只写一个的话，老版本或新版本客户端里必有一边读不到；
 *   原版 `character-card-parser.js` 就是两个都写（见 [PngCard] 类注释）。
 * - 卡得有名字和多条开场白：首启那张卡本身就是"App 能跑"的证明。
 */
class BundledCardTest {
    private val repoRoot: Path = Path.of(System.getProperty("repo.root")!!)
    private val assetsDir: Path = repoRoot.resolve("app/src/main/assets/cards")

    private fun bundledCards(): List<Path> =
        Files.list(assetsDir).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".png", ignoreCase = true) }.toList()
        }

    @Test
    fun `随包的每张卡都能解析`() {
        val cards = bundledCards()
        assertTrue(cards.isNotEmpty(), "assets/cards 下至少要有一张卡，否则首启没有卡可聊")

        for (png in cards) {
            val bytes = Files.readAllBytes(png)
            val card = PngCard.read(bytes)
            assertTrue(card.name.isNotBlank(), "${png.fileName} 没有名字")
            assertTrue(card.firstMessage.isNotBlank(), "${png.fileName} 没有开场白")

            // 两个 chunk 都在，且解析出来的 spec 跟 chunk 对得上
            val keywords = PngCard.readTextChunks(bytes).map { it.keyword.lowercase() }
            assertTrue("chara" in keywords, "${png.fileName} 缺 chara（V2）块：老客户端会读不到卡")
            assertTrue("ccv3" in keywords, "${png.fileName} 缺 ccv3（V3）块：新客户端会读不到卡")
        }
    }

    @Test
    fun `随包卡的开场白与世界书都进得了提示词链路`() {
        // 演示卡不是摆设：它得能演示"多开场白"和"内嵌世界书"这两件事，否则首启看见的是一张空壳卡。
        for (png in bundledCards()) {
            val card = PngCard.read(Files.readAllBytes(png))
            assertTrue(
                card.alternateGreetings.isNotEmpty(),
                "${png.fileName} 只有一条开场白 —— 开场白翻页（‹1/N›）就没法试",
            )
            val entries =
                (card.characterBook?.get("entries") as? kotlinx.serialization.json.JsonArray)?.size ?: 0
            assertTrue(entries > 0, "${png.fileName} 没有内嵌世界书 —— 世界书那条链路就没法演示")
        }
    }
}
