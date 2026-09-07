package io.legado.app.help.tts

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KokoroCharacterVoicesTest {

    @Test
    fun `character override is exact normalized and book local`() {
        val first = Book(bookUrl = "book-a", name = "A", author = "a")
        val second = Book(bookUrl = "book-b", name = "B", author = "b")
        first.config.kokoroCharacterVoices = mapOf(" 张三 " to 66)

        assertEquals(66, KokoroCharacterVoices.speakerOverride(first, "张三"))
        assertNull(KokoroCharacterVoices.speakerOverride(first, "李四"))
        assertNull(KokoroCharacterVoices.speakerOverride(second, "张三"))
    }

    @Test
    fun `invalid speaker ids are clamped while invalid names are dropped`() {
        val sanitized = KokoroCharacterVoices.sanitize(
            mapOf("张三" to -100, "李四" to 999, "他" to 60, "A" to 58)
        )
        assertEquals(KokoroOfflineTts.SPEAKER_MIN, sanitized["张三"])
        assertEquals(KokoroOfflineTts.SPEAKER_MAX, sanitized["李四"])
        assertEquals(setOf("张三", "李四"), sanitized.keys)
    }
}
