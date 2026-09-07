package io.legado.app.ui.book.read

import io.legado.app.model.resolveContentFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XuanjuanReaderRecoveryContractTest {

    @Test
    fun `reader recognizes both content failure forms`() {
        assertEquals("timeout", resolveContentFailureReason("获取正文失败\ntimeout"))
        assertEquals("没有书源", resolveContentFailureReason("加载正文失败\n没有书源"))
        assertNull(resolveContentFailureReason("这是正常正文"))
    }

    @Test
    fun `reader exposes explicit recovery actions`() {
        val layout = source("app/src/main/res/layout/activity_book_read.xml")
        listOf(
            "@+id/read_recovery",
            "@+id/btn_read_retry",
            "@+id/btn_read_use_cache",
            "@+id/btn_read_change_source",
            "@string/xuanjuan_reader_recovery_title",
            "@string/xuanjuan_reader_retry",
            "@string/xuanjuan_reader_use_cache",
            "@string/xuanjuan_reader_recovery_change_source",
        ).forEach { assertTrue("missing $it", layout.contains(it)) }
    }

    @Test
    fun `recovery state is current chapter scoped and source aware`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        assertTrue(readBook.contains("data class ContentRecoveryState"))
        assertTrue(readBook.contains("contentFailureReasons = ConcurrentHashMap<Int, String>()"))
        assertTrue(readBook.contains("if (chapter.index != durChapterIndex) return"))
        assertTrue(readBook.contains("canChangeSource = !book.isLocal"))
        assertTrue(readBook.contains("contentRecoveryState = null"))
    }

    @Test
    fun `prefetched chapter failure follows that chapter when navigation promotes it`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        val recovery = section(
            readBook,
            "private fun updateContentRecoveryState",
            "suspend fun buildReplacePreview",
        )
        assertTrue(recovery.contains("contentFailureReasons[chapter.index] = failureReason"))
        assertTrue(recovery.contains("contentFailureReasons.remove(chapter.index)"))
        assertTrue(recovery.contains("private fun activateCurrentContentRecoveryState()"))
        assertTrue(readBook.countText("activateCurrentContentRecoveryState()") >= 4)
    }

    @Test
    fun `retry keeps cache intact and returns to normal cache first load path`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        val retry = section(readBook, "fun retryCurrentContent()", "suspend fun useCachedCurrentContent()")
        assertTrue(retry.contains("curTextChapter = null"))
        assertTrue(retry.contains("loadContent("))
        assertFalse(retry.contains("BookHelp.delContent"))
    }

    @Test
    fun `cached recovery is strictly cache only`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        val cached = section(readBook, "suspend fun useCachedCurrentContent()", "private fun usableCachedContent")
        val cacheLookup = section(readBook, "private fun usableCachedContent", "private fun updateContentRecoveryState")
        assertTrue(cacheLookup.contains("BookHelp.getContent"))
        assertTrue(cached.contains("contentLoadFinish("))
        assertFalse(cached.contains("download("))
        assertFalse(cacheLookup.contains("download("))
    }

    @Test
    fun `activity wires all recovery actions and hides inapplicable ones`() {
        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val created = section(activity, "override fun onActivityCreated", "override fun onPostCreate")
        val recovery = section(activity, "private fun updateReadRecovery()", "/**\n     * 显示菜单")
        assertTrue(created.contains("binding.btnReadRetry.setOnClickListener"))
        assertTrue(created.contains("ReadBook.retryCurrentContent()"))
        assertTrue(created.contains("binding.btnReadUseCache.setOnClickListener"))
        assertTrue(created.contains("ReadBook.useCachedCurrentContent()"))
        assertTrue(created.contains("binding.btnReadChangeSource.setOnClickListener"))
        assertTrue(created.contains("showBookChangeSource()"))
        assertTrue(recovery.contains("btnReadUseCache.isVisible = state.hasCachedContent"))
        assertTrue(recovery.contains("btnReadChangeSource.isVisible = state.canChangeSource"))
    }

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        require(startIndex >= 0 && endIndex > startIndex) { "Missing section $start .. $end" }
        return source.substring(startIndex, endIndex)
    }

    private fun String.countText(value: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = indexOf(value, start)
            if (index < 0) return count
            count++
            start = index + value.length
        }
    }

    private fun source(path: String): String {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            val candidate = File(current, path)
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            current = current.parentFile ?: return@repeat
        }
        error("Project file not found: $path")
    }
}
