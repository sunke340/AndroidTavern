package app.tavern.core.card

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32

/**
 * 角色卡 ↔ PNG 的编解码：卡片 JSON 存在 PNG 的 `tEXt` 块里，key 为 `chara`（V2）或 `ccv3`（V3）。
 *
 * 两个必须照抄原版的地方（见 `src/character-card-parser.js`）：
 *
 * 1. **读的时候 `ccv3` 优先**，没有再退回 `chara`。
 * 2. **写的时候两个都写**：同一条 JSON 写进 `chara`，再把它标成 `chara_card_v3` / `3.0` 写进 `ccv3`。
 *    原版这么干是为了让老版本（只认 chara）和新版本（只认 ccv3）都能读。
 *
 * 写的时候只动 `tEXt` 里的这两个 key，其余 chunk（IHDR/IDAT/…）**逐字节原样搬**——
 * 绝不能把图重新编码一遍，那样会掉画质、变大、还会丢掉其他工具塞的信息。
 */
public object PngCard {
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private const val KEY_CHARA = "chara"
    private const val KEY_CCV3 = "ccv3"

    public fun read(image: ByteArray): CharacterCard {
        val texts = readTextChunks(image)

        val payload = texts.firstOrNull { it.keyword.equals(KEY_CCV3, ignoreCase = true) }?.text
            ?: texts.firstOrNull { it.keyword.equals(KEY_CHARA, ignoreCase = true) }?.text

        requireNotNull(payload) { "这张 PNG 里没有 chara/ccv3 卡片数据" }

        val json = String(Base64.getDecoder().decode(payload.trim()), Charsets.UTF_8)
        return CharacterCard.fromJson(json)
    }

    public fun write(image: ByteArray, card: CharacterCard): ByteArray {
        val chunks = readChunks(image)
        val v2Json = card.raw.toString()
        val v3Json = JsonObject(
            card.raw + mapOf(
                "spec" to JsonPrimitive("chara_card_v3"),
                "spec_version" to JsonPrimitive("3.0"),
            ),
        ).toString()

        val out = ByteArrayOutputStream(image.size + 4096)
        out.write(signature)

        var inserted = false
        for (chunk in chunks) {
            if (chunk.type == "tEXt") {
                val key = keywordOf(chunk.data)
                if (key != null && (key.equals(KEY_CHARA, true) || key.equals(KEY_CCV3, true))) continue
            }
            if (chunk.type == "IEND" && !inserted) {
                out.writeChunk(textChunk(KEY_CHARA, v2Json))
                out.writeChunk(textChunk(KEY_CCV3, v3Json))
                inserted = true
            }
            out.writeChunk(chunk)
        }

        // 没找到 IEND（损坏的 PNG）：退化成追加，至少数据还在
        if (!inserted) {
            out.writeChunk(textChunk(KEY_CHARA, v2Json))
            out.writeChunk(textChunk(KEY_CCV3, v3Json))
        }

        return out.toByteArray()
    }

    /** 读出所有 `tEXt` 块，调试和测试用。 */
    public fun readTextChunks(image: ByteArray): List<TextChunk> =
        readChunks(image)
            .filter { it.type == "tEXt" }
            .mapNotNull { chunk ->
                val zero = chunk.data.indexOf(0)
                if (zero <= 0) return@mapNotNull null
                TextChunk(
                    keyword = String(chunk.data, 0, zero, Charsets.ISO_8859_1),
                    text = String(chunk.data, zero + 1, chunk.data.size - zero - 1, Charsets.ISO_8859_1),
                )
            }

    public data class TextChunk(public val keyword: String, public val text: String)

    // ---- 内部：PNG chunk 读写 ----

    private data class Chunk(val type: String, val data: ByteArray)

    private fun readChunks(image: ByteArray): List<Chunk> {
        require(image.size > 8 && image.copyOfRange(0, 8).contentEquals(signature)) { "不是 PNG（签名不符）" }

        val chunks = mutableListOf<Chunk>()
        var offset = 8
        while (offset + 8 <= image.size) {
            val length = readInt(image, offset)
            require(length >= 0 && offset + 12 + length <= image.size) { "PNG chunk 长度异常：$length" }
            val type = String(image, offset + 4, 4, Charsets.ISO_8859_1)
            val data = image.copyOfRange(offset + 8, offset + 8 + length)
            chunks += Chunk(type, data)
            offset += 12 + length
        }
        return chunks
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun keywordOf(data: ByteArray): String? {
        val zero = data.indexOf(0)
        if (zero <= 0) return null
        return String(data, 0, zero, Charsets.ISO_8859_1)
    }

    private fun textChunk(keyword: String, json: String): Chunk {
        val payload = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val body = keyword.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            payload.toByteArray(Charsets.ISO_8859_1)
        return Chunk("tEXt", body)
    }

    private fun ByteArrayOutputStream.writeChunk(chunk: Chunk) {
        val typeBytes = chunk.type.toByteArray(Charsets.ISO_8859_1)
        write(intBytes(chunk.data.size))
        write(typeBytes)
        write(chunk.data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(chunk.data)
        write(intBytes(crc.value.toInt()))
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
