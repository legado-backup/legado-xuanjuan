package io.legado.app.help.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSpeechSegmenterTest {

    @Test
    fun `plain prose is narrator`() {
        val segments = NovelSpeechSegmenter.segment("夜色渐深，风吹过长街。")
        assertEquals(listOf(SpeechRole.NARRATOR), segments.map { it.role })
    }

    @Test
    fun `explicit female attribution uses female voice`() {
        val text = "“你终于来了。”她轻声道，随后关上门。"
        val dialogue = NovelSpeechSegmenter.segment(text).first { it.text.startsWith("“") }
        assertEquals(SpeechRole.FEMALE, dialogue.role)
    }

    @Test
    fun `explicit male attribution before quote uses male voice`() {
        val text = "他问道：“我们现在出发吗？”窗外还在下雨。"
        val dialogue = NovelSpeechSegmenter.segment(text).first { it.text.startsWith("“") }
        assertEquals(SpeechRole.MALE, dialogue.role)
    }

    @Test
    fun `name alone never guesses gender`() {
        val text = "李明说道：“走吧。”"
        val dialogue = NovelSpeechSegmenter.segment(text).first { it.text.startsWith("“") }
        assertEquals(SpeechRole.UNKNOWN_DIALOGUE, dialogue.role)
        assertEquals("李明", dialogue.characterName)
    }

    @Test
    fun `speaker name is discovered before and after dialogue`() {
        val before = NovelSpeechSegmenter.segment("张三说道：“现在出发。”")
            .first { it.text.startsWith("“") }
        val after = NovelSpeechSegmenter.segment("“别回头。”李四问道。")
            .first { it.text.startsWith("“") }
        assertEquals("张三", before.characterName)
        assertEquals("李四", after.characterName)
    }

    @Test
    fun `generic role words and pronouns never become character names`() {
        val generic = NovelSpeechSegmenter.segment("女子喊道：“快走！”")
            .first { it.text.startsWith("“") }
        val pronoun = NovelSpeechSegmenter.segment("她说道：“知道了。”")
            .first { it.text.startsWith("“") }
        assertEquals(SpeechRole.FEMALE, generic.role)
        assertEquals(null, generic.characterName)
        assertEquals(null, pronoun.characterName)
    }

    @Test
    fun `character discovery counts occurrences and keeps uncertain gender`() {
        val text = "张三说道：“一。”张三问道：“二？”李四说道：“三。”"
        val characters = NovelSpeechSegmenter.discoverCharacters(text)
        assertEquals("张三", characters.first().name)
        assertEquals(2, characters.first().occurrences)
        assertEquals(SpeechRole.UNKNOWN_DIALOGUE, characters.first().role)
        assertEquals("一。", characters.first().previewText)
        assertTrue(characters.any { it.name == "李四" && it.occurrences == 1 })
    }

    @Test
    fun `mixed segmentation preserves original text and offsets`() {
        val text = "门开了。“别动！”女子喊道。众人停住脚步。"
        val segments = NovelSpeechSegmenter.segment(text)
        assertEquals(text, segments.joinToString("") { it.text })
        segments.forEach { segment ->
            assertEquals(segment.text, text.substring(segment.start, segment.start + segment.text.length))
        }
        assertTrue(segments.any { it.role == SpeechRole.FEMALE })
    }

    @Test
    fun `long text chunks remain bounded and keep source offsets`() {
        val text = "他问道：“" + "这是很长的一句话，".repeat(30) + "结束了吗？”"
        val chunks = NovelSpeechSegmenter.chunk(text, maxChars = 80)
        assertTrue(chunks.all { it.text.length <= 80 })
        chunks.forEach { chunk ->
            assertEquals(chunk.text, text.substring(chunk.start, chunk.start + chunk.text.length))
        }
    }
}
