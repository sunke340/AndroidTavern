package app.tavern.core.card

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PngCardTest {
    private val repoRoot: Path = Path.of(System.getProperty("repo.root")!!)

    private fun synthetic(): ByteArray = Files.readAllBytes(repoRoot.resolve("fixtures/cards/P0-5-synthetic.png"))

    @Test
    fun `读合成卡：字段与生成脚本一致`() {
        val card = PngCard.read(synthetic())

        assertEquals("Synthetic", card.name)
        assertEquals("PERS-BETA", card.personality)
        assertEquals("SCEN-GAMMA", card.scenario)
        assertEquals("FIRST-OMEGA", card.firstMessage)
        assertContains(card.description, "DESC-ALPHA")
        assertContains(card.messageExample, "EXAMPLE-DELTA")
        assertEquals("2.0", card.specVersion)
    }

    @Test
    fun `读原版真卡：ccv3 优先，内嵌世界书能拿到`() {
        val path = repoRoot.resolve("vendor/SillyTavern/data/default-user/characters/Seraphina.png")
        assumeTrue(Files.exists(path), "没有 vendor 目录，跳过（CI 常态）")

        val card = PngCard.read(Files.readAllBytes(path))

        assertEquals("Seraphina", card.name)
        // 两个 chunk 都在，读出来的是 ccv3（V3）：spec 应被原版标成 chara_card_v3
        assertEquals("chara_card_v3", card.spec)
        assertEquals(2851, card.description.length)
        assertContains(card.firstMessage, "You wake with a start")

        val book = card.characterBook
        assertEquals(true, book != null, "Seraphina 应带 character_book")
        assertEquals(4, book!!["entries"]?.let { (it as kotlinx.serialization.json.JsonArray).size } ?: 0)
    }

    @Test
    fun `V1 扁平卡也能读`() {
        val card = CharacterCard.fromJson(
            """{"name":"Old","description":"OLD-DESC","first_mes":"OLD-FIRST","mes_example":""}""",
        )

        assertEquals("Old", card.name)
        assertEquals("OLD-DESC", card.description)
        assertEquals("OLD-FIRST", card.firstMessage)
        assertEquals(null, card.spec)
    }

    @Test
    fun `写回：只改卡片字段，图片数据一个字节都不动`() {
        val original = synthetic()
        val card = PngCard.read(original)
        val updated = card.withField("description", kotlinx.serialization.json.JsonPrimitive("NEW-DESC"))

        val rewritten = PngCard.write(original, updated)
        val reread = PngCard.read(rewritten)

        assertEquals("NEW-DESC", reread.description)
        assertEquals(card.personality, reread.personality, "其他字段不该被弄丢")

        // 图片本身逐字节不变（IDAT 是压缩后的像素数据，重编码必然变大/变样）
        assertEquals(idatBytes(original).toList(), idatBytes(rewritten).toList(), "IDAT 被改动了，说明重新编码了图片")

        // tEXt 里与卡片相关的只剩 chara + ccv3
        val keywords = PngCard.readTextChunks(rewritten)
            .map { it.keyword.lowercase() }
            .filter { it == "chara" || it == "ccv3" }
        assertEquals(listOf("chara", "ccv3"), keywords)
    }

    @Test
    fun `写回的 ccv3 被标成 V3`() {
        val rewritten = PngCard.write(synthetic(), PngCard.read(synthetic()))
        val v3 = PngCard.readTextChunks(rewritten).first { it.keyword.equals("ccv3", true) }

        val json = String(java.util.Base64.getDecoder().decode(v3.text), Charsets.UTF_8)
        assertContains(json, "\"chara_card_v3\"")
        assertContains(json, "\"3.0\"")
    }

    @Test
    fun `没有卡片数据的 PNG 要报错而不是返回空卡`() {
        // 从合成卡出发，把所有 tEXt 卡片块替换成无关块
        val stripped = stripCardText(synthetic())

        assertFailsWith<IllegalArgumentException> { PngCard.read(stripped) }
    }

    // ---- 测试自带的极简 PNG 解析：只为了比较 IDAT ----

    private fun chunksOf(image: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var i = 8
        while (i + 8 <= image.size) {
            val len = ((image[i].toInt() and 0xFF) shl 24) or ((image[i + 1].toInt() and 0xFF) shl 16) or
                ((image[i + 2].toInt() and 0xFF) shl 8) or (image[i + 3].toInt() and 0xFF)
            val type = String(image, i + 4, 4, Charsets.ISO_8859_1)
            out += type to image.copyOfRange(i + 8, i + 8 + len)
            i += 12 + len
        }
        return out
    }

    private fun idatBytes(image: ByteArray): ByteArray =
        chunksOf(image).filter { it.first == "IDAT" }.flatMap { it.second.toList() }.toByteArray()

    private fun stripCardText(image: ByteArray): ByteArray {
        val chunks = chunksOf(image).filterNot { (type, data) ->
            val keyword = String(data, 0, minOf(5, data.size), Charsets.ISO_8859_1)
            type == "tEXt" && (keyword == "chara" || keyword == "ccv3")
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(image, 0, 8)
        for ((type, data) in chunks) {
            val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
            out.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
            out.write(typeBytes)
            out.write(data)
            val crc = java.util.zip.CRC32()
            crc.update(typeBytes)
            crc.update(data)
            val c = crc.value.toInt()
            out.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
        }
        return out.toByteArray()
    }
}
