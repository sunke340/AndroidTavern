package app.tavern.core.card

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 世界书文件解析：用原版**自己转换出来**的文件当夹具（`fixtures/worldinfo/P1-2-logic-world.json`，
 * 就是原版导入那张合成卡的内嵌书后落在 `worlds/` 下的东西），所以断言的是真格式。
 */
class WorldBookFileTest {
    private val repoRoot: Path = Path.of(System.getProperty("repo.root") ?: error("缺少 repo.root"))

    private fun load(name: String, file: String): WorldBookFile {
        val path = repoRoot.resolve("fixtures/worldinfo/$file")
        require(Files.exists(path)) { "夹具不存在：$path" }
        return parseWorldBook(name, Files.readString(path))
    }

    @Test
    fun `解析原版世界书文件：条目数、关键字、逻辑都在`() {
        val book = load("WILogic", "P1-2-logic-world.json")

        assertEquals("WILogic", book.name)
        assertEquals(5, book.entries.size)

        val first = book.entries.first { it.uid == 0 }
        assertEquals(listOf("alpha"), first.keys)
        assertEquals(listOf("beta"), first.secondaryKeys)
        assertEquals("MARK-ANDANY", first.content)
        assertTrue(first.selective)
        // 文件里是 "order": 100（原版 newWorldInfoEntryTemplate 的默认插入序号）
        assertEquals(100, first.order)
        assertEquals(false, first.disabled)
    }

    @Test
    fun `四种 selectiveLogic 都按数字读出来`() {
        val book = load("WILogic", "P1-2-logic-world.json")
        val logics = book.entries.sortedBy { it.uid }.map { it.selectiveLogic }
        assertEquals(
            listOf(
                app.tavern.core.lorebook.SelectiveLogic.AndAny,
                app.tavern.core.lorebook.SelectiveLogic.NotAll,
                app.tavern.core.lorebook.SelectiveLogic.NotAny,
                app.tavern.core.lorebook.SelectiveLogic.AndAll,
                app.tavern.core.lorebook.SelectiveLogic.NotAny,
            ),
            logics,
        )
    }

    @Test
    fun `没有 entries 的文件解析成空书，不抛异常`() {
        val book = parseWorldBook("broken", "{}")
        assertEquals("broken", book.name)
        assertTrue(book.entries.isEmpty())
    }
}
