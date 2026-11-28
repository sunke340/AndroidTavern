package app.tavern.data.config

import app.tavern.core.model.ConfigOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三级配置的值语义（`ConfigOverride`）。
 *
 * 为什么只测这一层：`data` 里绝大多数东西都挂在 `SharedPreferences` / `File` 上，
 * 本机没有 Android SDK、也没有 Robolectric，只有**纯值对象**能在 CI 的
 * `:data:testDebugUnitTest` 里真跑。这比"data 层零测试"强，也诚实 ——
 * 编解码与迁移要么抽成纯函数（再做），要么只能靠真机（见 docs/dev/status.md）。
 */
class ConfigOverrideTest {
    @Test
    fun `默认对象是空的（= 三层都不覆盖）`() {
        assertTrue(ConfigOverride().isEmpty)
    }

    @Test
    fun `任何一项被填上就不再是空（含今晚新增的 persona 与作者注位置）`() {
        assertFalse(ConfigOverride(personaId = "p1").isEmpty)
        assertFalse(ConfigOverride(authorNote = "x").isEmpty)
        assertFalse(ConfigOverride(authorNoteDepth = 2).isEmpty)
        assertFalse(ConfigOverride(authorNoteRole = "User").isEmpty)
        assertFalse(ConfigOverride(authorNoteInterval = 3).isEmpty)
        assertFalse(ConfigOverride(authorNotePosition = 2).isEmpty)
        assertFalse(ConfigOverride(bubbleFontSize = 16f).isEmpty)
        assertFalse(ConfigOverride(greetingIndex = 0).isEmpty)
    }

    @Test
    fun `空集不等于 null —— 「全都不要」是合法覆盖`() {
        assertFalse(ConfigOverride(worldBookNames = emptySet()).isEmpty)
        assertFalse(ConfigOverride(regexIds = emptySet()).isEmpty)
        // 内置正则的「明确全开」也是覆盖
        assertFalse(ConfigOverride(builtinDisabled = emptySet()).isEmpty)
        assertNull(ConfigOverride().worldBookNames)
    }

    @Test
    fun `作用域键的拼法固定（DB 与 SharedPreferences 都按它找）`() {
        assertEquals("session:abc", ConfigOverrideRepository.session("abc"))
        assertEquals("card:xyz", ConfigOverrideRepository.card("xyz"))
    }
}
