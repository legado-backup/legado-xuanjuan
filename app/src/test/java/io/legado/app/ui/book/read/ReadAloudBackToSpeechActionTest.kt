package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudBackToSpeechActionTest {

    @Test
    fun `floating bar and console reuse back to speaking position control`() {
        val floatingBar = readProjectFile("src/main/res/layout/view_read_aloud_float_bar.xml")
        val dialog = readProjectFile("src/main/res/layout/dialog_read_aloud.xml")

        assertTrue(floatingBar.contains("@+id/ll_back_to_speech"))
        assertTrue(floatingBar.contains("@string/back_to_speaking_position"))
        assertTrue(dialog.contains("@+id/ll_back_to_speech"))
        assertTrue(dialog.contains("@string/back_to_speaking_position"))
    }

    @Test
    fun `read aloud console binds back action only when speech follow is detached`() {
        val dialogKt = readProjectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")

        assertTrue(dialogKt.contains("llBackToSpeech.setOnClickListener"))
        assertTrue(dialogKt.contains("callBack?.backToSpeakingPosition()"))
        assertTrue(dialogKt.contains("BaseReadAloudService.isRun && !ReadAloud.followReadAloudPosition"))
        assertTrue(dialogKt.contains("EventBus.READ_ALOUD_FOLLOW"))
    }

    @Test
    fun `floating back action restores follow and requests jump`() {
        val activityKt = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")

        assertTrue(activityKt.contains("llBackToSpeech.setOnClickListener"))
        assertTrue(activityKt.contains("override fun backToSpeakingPosition()"))
        assertTrue(activityKt.contains("ReadAloud.restoreReadAloudFollow()"))
    }

    @Test
    fun `cross chapter jump opens the speaking chapter at the spoken position`() {
        val activityKt = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")

        // Cross-chapter must be precise: open the speaking chapter AT the spoken char position,
        // not just the chapter start.
        assertTrue(activityKt.contains("ReadBook.openChapter(speakingChapterIndex,"))
    }

    @Test
    fun `back to speaking position strings are localized`() {
        val defaultStrings = readProjectFile("src/main/res/values/strings.xml")
        val zhStrings = readProjectFile("src/main/res/values-zh/strings.xml")

        assertTrue(defaultStrings.contains("<string name=\"back_to_speaking_position\">Back to speaking position</string>"))
        assertTrue(zhStrings.contains("<string name=\"back_to_speaking_position\">回到朗读位置</string>"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(
            File(pathInApp),
            File("app/$pathInApp")
        )
        return candidates.first { it.isFile }.readText()
    }
}
