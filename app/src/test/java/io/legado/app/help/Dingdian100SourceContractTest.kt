package io.legado.app.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Dingdian100SourceContractTest {

    @Test
    fun tocUsesFullOrderedChapterListInsteadOfWholePageLinks() {
        val source = projectFile(
            "src/main/assets/defaultData/bookSources/dingdian100.js"
        ).readText().replace("\r\n", "\n")

        val getChapters = source.substringAfter("function getChapters(book)")
            .substringBefore("function getContent")

        assertTrue(getChapters.contains("a[href*=\"/chapter/"))
        assertTrue(getChapters.contains("new RegExp(\"/chapter/\"+bid+"))
        assertFalse(getChapters.contains("#all_chapter"))
        assertFalse(getChapters.contains(", a[href*="))
    }

    private fun projectFile(path: String): File = sequenceOf(File(path), File("app/$path"))
        .first(File::isFile)
}
