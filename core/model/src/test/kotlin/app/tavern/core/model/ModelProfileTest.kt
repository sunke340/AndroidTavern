package app.tavern.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 档案的纯逻辑：默认项归一化、请求头文本编解码。
 *
 * 这两件事出错都很难在真机上发现（"点了发送不知道用哪个模型""填了头但没生效"），
 * 所以放在本机能跑的回路里钉死。
 */
class ModelProfileTest {

    private fun profile(id: String, isDefault: Boolean = false) = ModelProfile(
        id = id,
        name = id,
        baseUrl = "https://example.com/v1",
        model = "m",
        isDefault = isDefault,
    )

    @Test
    fun `没有默认时把第一条变成默认`() {
        val result = ModelProfiles.normalizeDefault(listOf(profile("a"), profile("b")))

        assertEquals(listOf(true, false), result.map { it.isDefault })
        assertEquals("a", ModelProfiles.active(result)?.id)
    }

    @Test
    fun `多个默认只留第一个`() {
        val result = ModelProfiles.normalizeDefault(
            listOf(profile("a", true), profile("b", true), profile("c")),
        )

        assertEquals(listOf(true, false, false), result.map { it.isDefault })
    }

    @Test
    fun `已经正确时原样返回`() {
        val input = listOf(profile("a"), profile("b", true))
        val result = ModelProfiles.normalizeDefault(input)

        assertEquals(input, result)
    }

    @Test
    fun `空列表不崩`() {
        assertEquals(emptyList(), ModelProfiles.normalizeDefault(emptyList()))
        assertNull(ModelProfiles.active(emptyList()))
    }

    @Test
    fun `请求头文本往返一致`() {
        val headers = linkedMapOf(
            "X-Title" to "AndroidTavern",
            "HTTP-Referer" to "https://example.com/a:b",
        )

        val text = ModelProfiles.formatHeaders(headers)

        assertEquals("X-Title: AndroidTavern\nHTTP-Referer: https://example.com/a:b", text)
        assertEquals(headers, ModelProfiles.parseHeaders(text))
    }

    @Test
    fun `解析时忽略空行注释和坏行，值里的冒号保留`() {
        val text = """
            # 这是注释

            X-Title: 酒馆
            没有冒号的行
            : 空名字
            Authorization:
            X-Url: https://a.b/c?d=1:2
        """.trimIndent()

        val parsed = ModelProfiles.parseHeaders(text)

        assertEquals(
            linkedMapOf("X-Title" to "酒馆", "X-Url" to "https://a.b/c?d=1:2"),
            parsed,
        )
    }

    @Test
    fun `Key 名带上档案 id，互相不覆盖`() {
        assertEquals("api_key:default", ModelProfiles.keyName("default"))
        assertTrue(ModelProfiles.keyName("a") != ModelProfiles.keyName("b"))
    }
}
