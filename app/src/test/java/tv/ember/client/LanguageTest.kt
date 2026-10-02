package tv.ember.client

import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import tv.ember.client.i18n.*
import tv.ember.client.settings.BufferMode
import tv.ember.client.settings.PlayerChoice

class LanguageTest {
    @After fun restoreEnglish() { Tr.language="en" }
    @Test fun englishIsTheDefaultAndUnsupportedPreferencesFallBack() {
        assertEquals("en",AppLanguage.normalize(null));assertEquals("en",AppLanguage.normalize("de"))
        assertEquals("en",AppLanguage.normalize("en"));assertEquals("zh",AppLanguage.normalize("zh"))
    }
    @Test fun catalogPreservesArgumentsAndNumericFormatSpecifiers() {
        val args=Regex("\\{\\d+\\}");val format=Regex("%[-+.#0-9]*[a-zA-Z%]")
        UiText.entries.forEach { value ->
            assertTrue(value.name,value.english.isNotBlank());assertTrue(value.name,value.chinese.isNotBlank())
            assertFalse(value.name,Regex("[\\u3400-\\u9fff]").containsMatchIn(value.english))
            assertEquals(value.name,args.findAll(value.chinese).map { it.value }.toList(),args.findAll(value.english).map { it.value }.toList())
            assertEquals(value.name,format.findAll(value.chinese).map { it.value }.toList(),format.findAll(value.english).map { it.value }.toList())
        }
    }
    @Test fun changingLanguageUpdatesExistingEnumLabels() {
        val player=PlayerChoice.INTERNAL;val buffer=BufferMode.AUTO
        Tr.language="en";assertEquals("Internal player",player.label);assertEquals("Auto",buffer.label)
        Tr.language="zh";assertEquals("内置播放器",player.label);assertEquals("自动",buffer.label)
    }
    @Test fun formattingKeepsServerContentAndBracesIntact() {
        Tr.language="en"
        val title="星际 {1} \$source"
        assertEquals("Opening $title…",Tr.text(UiText.OPENING_147,title))
        assertEquals("Watched 68% · 1h 12m remaining",Tr.text(UiText.WATCHED_261,68,"1h 12m remaining"))
        Tr.language="zh";assertEquals("正在打开 $title…",Tr.text(UiText.OPENING_147,title))
    }
}
