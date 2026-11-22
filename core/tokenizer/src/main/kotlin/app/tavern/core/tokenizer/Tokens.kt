package app.tavern.core.tokenizer

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType

/**
 * token 计数。给「世界书预算」「上下文裁剪」用，行为对齐原版前端的 `getTokenCountAsync`
 * （`public/scripts/tokenizers.js:443`）——它也是按模型挑 BPE 词表。
 *
 * 词表用 jtokkit 的 `cl100k_base` / `o200k_base`，与 OpenAI 官方 tiktoken 同源；
 * 对齐性由 `fixtures/expected/P1-6-tokens.json`（原版页面里实测的计数）断言，不靠"应该一样"。
 *
 * **不支持** Claude / Llama / Mistral 那些需要 sentencepiece 或专门词表的模型：
 * 原版对它们是走 server 端的 `/api/tokenizers/` 各编码接口的，我们没接。这些模型统一回落到 cl100k
 * （只会让计数偏大，不会让预算无界）。
 */
public object Tokens {

    /** 按模型选词表后数 token。 */
    public fun count(model: String, text: String): Int {
        if (text.isEmpty()) return 0
        return encodingFor(model).countTokens(text)
    }

    /**
     * 把文本当**一条聊天消息**计费时的 token 数。
     *
     * 世界书预算、上下文裁剪用的都是原版的这个数，而不是裸文本数：原版
     * `countTokensOpenAIAsync`（`public/scripts/tokenizers.js:846`）会打到 server 的
     * `/api/tokenizers/openai/count`，那边按 OpenAI 的规则算「每条消息 3 token 起 + 内容 + 3 token 收尾」
     * （`src/endpoints/tokenizers.js:1000-1016`，非 0301 模型）——也就是 **裸计数 + 6**。
     * 这个 +6 不是误差，是原版真实行为：一条 10 token 的条目在预算里占 16。
     */
    public fun countAsMessage(model: String, text: String): Int {
        if (text.isEmpty()) return 0
        return count(model, text) + MESSAGE_OVERHEAD
    }

    /** 某个模型用哪套词表。给测试和调试看。 */
    public fun encodingName(model: String): String = encodingTypeFor(model).name

    private fun encodingFor(model: String): Encoding =
        CACHE.getOrPut(encodingTypeFor(model)) {
            Encodings.newDefaultEncodingRegistry().getEncoding(encodingTypeFor(model))
        }

    private val CACHE = HashMap<EncodingType, Encoding>()

    /** 每条消息的固定开销（3 开头 + 3 收尾），见 countAsMessage 的注释。 */
    private const val MESSAGE_OVERHEAD = 6

    private fun encodingTypeFor(model: String): EncodingType {
        val name = model.lowercase().substringAfterLast('/')
        return when {
            name.startsWith("gpt-4o") -> EncodingType.O200K_BASE
            name.startsWith("chatgpt-4o") -> EncodingType.O200K_BASE
            name.startsWith("gpt-4.1") -> EncodingType.O200K_BASE
            name.startsWith("o1") -> EncodingType.O200K_BASE
            name.startsWith("o3") -> EncodingType.O200K_BASE
            name.startsWith("o4") -> EncodingType.O200K_BASE
            // gpt-4 / gpt-4-turbo / gpt-3.5 / 以及一切未知模型
            else -> EncodingType.CL100K_BASE
        }
    }
}
