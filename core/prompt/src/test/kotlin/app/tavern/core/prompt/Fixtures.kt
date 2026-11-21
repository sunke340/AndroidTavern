package app.tavern.core.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * 录下来的请求。`raw` 保留原始 JSON，方便以后断言 messages 之外的字段（model / stream …）。
 */
internal data class RecordedRequest(
    val messages: List<ChatMessage>,
    val userName: String,
    val characterName: String,
)

internal object Fixtures {
    /** 从 `fixtures/cards/` 读一张真卡（PNG 里的 chara/ccv3 chunk）。 */
    fun card(fileName: String): app.tavern.core.card.CharacterCard {
        val file = repoRoot.resolve("fixtures/cards/$fileName")
        require(Files.exists(file)) { "卡片不存在：$file" }
        return app.tavern.core.card.PngCard.read(Files.readAllBytes(file))
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val repoRoot: Path = Path.of(
        System.getProperty("repo.root") ?: error("缺少 repo.root 系统属性，检查 core/prompt/build.gradle.kts 的 tasks.test"),
    )

    fun load(id: String): RecordedRequest {
        val file = repoRoot.resolve("fixtures/expected/$id.json")
        require(Files.exists(file)) {
            "夹具不存在：$file（先起原版 ST，再跑 tools/fixture/capture.mjs）"
        }

        val root = json.parseToJsonElement(Files.readString(file)).jsonObject
        val request = root["request"]?.jsonObject ?: error("夹具缺少 request 字段")
        val context = root["_meta"]?.jsonObject?.get("context")?.jsonObject

        val messages = request["messages"]!!.jsonArray.map { element ->
            val obj = element.jsonObject
            ChatMessage(
                role = ChatRole.fromWire(obj["role"]!!.jsonPrimitive.content),
                content = obj["content"]!!.jsonPrimitive.content,
            )
        }

        return RecordedRequest(
            messages = messages,
            userName = context?.get("name1")?.jsonPrimitive?.content ?: "User",
            characterName = context?.get("name2")?.jsonPrimitive?.content ?: "Seraphina",
        )
    }
}
