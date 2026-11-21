package app.tavern.core.prompt

/**
 * 把卡里的 `mes_example` 拆成若干条示例消息。
 *
 * 移植自 `public/scripts/openai.js` 的 `setOpenAIMessageExamples` + `parseExampleIntoIndividual`。
 * 这段逻辑有三个反直觉的地方，都是逐字照抄原版、不是我的设计：
 *
 * 1. **第一行永远被丢掉**。原版假设它必然是 `<START>` 之类的开场标题。
 * 2. **`<START>` 先被换成 `{Example Dialogue:}`**，但那个标记本身不会出现在结果里 ——
 *    它由调用方换成 `[Example Chat]` 后**整块**插在前面（见 [SillyTavernDefaults.EXAMPLE_CHAT]）。
 * 3. **名字前缀被剥掉**，且用的是"删掉第一个出现的 `名字:`"而不是"去前缀"，
 *    所以正文中间出现 `Synthetic:` 也会被吃掉。
 */
internal object ExampleDialogue {
    fun parse(raw: String, characterName: String, userName: String): List<String> {
        if (raw.isBlank()) return emptyList()

        // 原版是先 substituteParams() 再解析，所以这里也先做宏替换——
        // 否则按名字判断块边界时，永远匹配不到 "{{user}}:" 这种原文。
        val normalized = raw
            .replace("{{char}}", characterName)
            .replace("{{user}}", userName)
            .replace(Regex("<START>", RegexOption.IGNORE_CASE), "{Example Dialogue:}")
            .replace("\r", "")

        val lines = normalized.split("\n")
        // 原版循环从 index 1 开始（跳过第一行），所以只有一行时结果必然为空
        if (lines.size <= 1) return emptyList()

        val messages = mutableListOf<String>()
        val current = mutableListOf<String>()
        var inUser = false
        var inBot = false

        fun flush(name: String) {
            messages += current.joinToString("\n").replaceFirst("$name:", "").trim()
            current.clear()
        }

        for (i in 1 until lines.size) {
            val line = lines[i]
            when {
                line.startsWith("$userName:") -> {
                    if (inBot) flush(characterName)
                    inUser = true
                    inBot = false
                }

                line.startsWith("$characterName:") -> {
                    if (inUser) flush(userName)
                    inBot = true
                    inUser = false
                }
            }
            current += line
        }

        // 块末尾没有"下一条消息"来触发切换，得单独收尾
        if (inUser) flush(userName) else if (inBot) flush(characterName)

        return messages
    }
}
