package io.legado.app.help.tts

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastVitsPlaybackPipelineStressInstrumentedTest {

    @Test
    fun productionPlaybackPipelineHandlesOnePointFiveXRapidDialogueSwitching() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Production Fast VITS model is missing: ${FastVitsOfflineTts.missingModelFiles(context)}",
            FastVitsOfflineTts.isInstalled(context),
        )

        val oldEngine = AppConfig.ttsEngine
        val oldSpeechRate = AppConfig.kokoroSpeechRate
        val oldEnableReadRecord = AppConfig.enableReadRecord
        val oldBook = ReadBook.book
        val oldChapterSize = ReadBook.chapterSize
        val oldSimulatedChapterSize = ReadBook.simulatedChapterSize
        val oldDurChapterIndex = ReadBook.durChapterIndex
        val oldDurChapterPos = ReadBook.durChapterPos
        val oldIsLocalBook = ReadBook.isLocalBook
        val oldPrevTextChapter = ReadBook.prevTextChapter
        val oldCurTextChapter = ReadBook.curTextChapter
        val oldNextTextChapter = ReadBook.nextTextChapter
        val oldBookSource = ReadBook.bookSource

        try {
            if (BaseReadAloudService.isRun) {
                ReadAloud.stop(context)
                waitUntil(SERVICE_STOP_TIMEOUT_MS) { !BaseReadAloudService.isRun }
            }
            assertFalse("A read-aloud service was still running before stress test", BaseReadAloudService.isRun)

            val stressText = buildStressText()
            val paragraphSegments = stressText.lineSequence()
                .filter(String::isNotBlank)
                .flatMap { NovelSpeechSegmenter.segment(it).asSequence() }
                .toList()
            val roles = paragraphSegments.map { it.role }.toSet()
            assertTrue("Narrator role missing from stress text", SpeechRole.NARRATOR in roles)
            assertTrue("Male role missing from stress text", SpeechRole.MALE in roles)
            assertTrue("Female role missing from stress text", SpeechRole.FEMALE in roles)
            assertTrue("Unknown-dialogue role missing from stress text", SpeechRole.UNKNOWN_DIALOGUE in roles)
            val roleTransitions = paragraphSegments.zipWithNext().count { (a, b) -> a.role != b.role }
            assertTrue("Stress text must switch roles frequently, transitions=$roleTransitions", roleTransitions >= 20)

            val syntheticBook = Book(
                bookUrl = "xuanjuan://fast-vits-playback-pipeline-stress",
                name = "玄卷极速离线压力测试",
                author = "instrumentation",
                totalChapterNum = 1,
            )
            val syntheticChapter = BookChapter(
                url = "xuanjuan://fast-vits-playback-pipeline-stress/chapter/0",
                title = "短对白压力测试",
                bookUrl = syntheticBook.bookUrl,
                index = 0,
            )

            AppConfig.ttsEngine = FastVitsOfflineTts.ENGINE_TOKEN
            AppConfig.kokoroSpeechRate = 10
            AppConfig.enableReadRecord = false

            ReadBook.book = syntheticBook
            ReadBook.chapterSize = 1
            ReadBook.simulatedChapterSize = 1
            ReadBook.durChapterIndex = 0
            ReadBook.durChapterPos = 0
            ReadBook.isLocalBook = true
            ReadBook.prevTextChapter = null
            ReadBook.curTextChapter = null
            ReadBook.nextTextChapter = null
            ReadBook.bookSource = null

            if (ChapterProvider.viewWidth <= 0 || ChapterProvider.viewHeight <= 0) {
                ChapterProvider.upStyle()
                ChapterProvider.upViewSize(TEST_VIEW_WIDTH, TEST_VIEW_HEIGHT)
            }

            ReadBook.contentLoadFinishAwait(
                book = syntheticBook,
                chapter = syntheticChapter,
                content = stressText,
                upContent = false,
                resetPageOffset = false,
            )
            val laidOutChapter = ReadBook.curTextChapter
            assertTrue("Synthetic chapter did not finish layout", laidOutChapter?.isCompleted == true)

            ReadAloud.upReadAloudClass()
            ReadBook.readAloud(play = true)

            assertTrue(
                "OfflineReadAloudService did not start",
                waitUntil(SERVICE_START_TIMEOUT_MS) { BaseReadAloudService.isRun && !BaseReadAloudService.pause },
            )
            val targetProgress = stressText.lineSequence()
                .filter(String::isNotBlank)
                .take(TARGET_PARAGRAPHS)
                .sumOf { it.length + 1 }
            val startedAt = SystemClock.elapsedRealtime()
            val progressed = waitUntil(PLAYBACK_PROGRESS_TIMEOUT_MS) {
                BaseReadAloudService.readAloudChapterStart >= targetProgress || !BaseReadAloudService.isRun
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val reachedProgress = BaseReadAloudService.readAloudChapterStart
            assertTrue(
                "Playback pipeline did not advance through $TARGET_PARAGRAPHS short paragraphs; " +
                    "targetProgress=$targetProgress reached=$reachedProgress running=${BaseReadAloudService.isRun}",
                progressed && reachedProgress >= targetProgress,
            )

            ReadAloud.stop(context)
            assertTrue(
                "OfflineReadAloudService did not stop cleanly",
                waitUntil(SERVICE_STOP_TIMEOUT_MS) { !BaseReadAloudService.isRun },
            )
            delay(750)

            val done =
                "FAST_VITS_PLAYBACK_PIPELINE_STRESS_DONE speed=1.5 paragraphs=${stressText.lines().size} " +
                    "targetParagraphs=$TARGET_PARAGRAPHS reachedProgress=$reachedProgress " +
                    "elapsedMs=$elapsedMs roleTransitions=$roleTransitions serviceRunning=${BaseReadAloudService.isRun}"
            Log.i(TAG, done)
            println(done)
        } finally {
            if (BaseReadAloudService.isRun) {
                ReadAloud.stop(context)
                waitUntil(SERVICE_STOP_TIMEOUT_MS) { !BaseReadAloudService.isRun }
            }

            AppConfig.ttsEngine = oldEngine
            AppConfig.kokoroSpeechRate = oldSpeechRate
            AppConfig.enableReadRecord = oldEnableReadRecord

            ReadBook.book = oldBook
            ReadBook.chapterSize = oldChapterSize
            ReadBook.simulatedChapterSize = oldSimulatedChapterSize
            ReadBook.durChapterIndex = oldDurChapterIndex
            ReadBook.durChapterPos = oldDurChapterPos
            ReadBook.isLocalBook = oldIsLocalBook
            ReadBook.prevTextChapter = oldPrevTextChapter
            ReadBook.curTextChapter = oldCurTextChapter
            ReadBook.nextTextChapter = oldNextTextChapter
            ReadBook.bookSource = oldBookSource

            ReadAloud.upReadAloudClass()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            delay(POLL_MS)
        }
        return predicate()
    }

    private fun buildStressText(): String = buildString {
        repeat(CYCLES) {
            appendLine("夜深了。")
            appendLine("“走吧。”他说。")
            appendLine("“别急。”她说。")
            appendLine("李明说道：“快走。”")
        }
    }.trimEnd()

    companion object {
        private const val TAG = "FastVitsPipelineStress"
        private const val CYCLES = 8
        private const val TARGET_PARAGRAPHS = 20
        private const val TEST_VIEW_WIDTH = 1080
        private const val TEST_VIEW_HEIGHT = 1920
        private const val POLL_MS = 100L
        private const val SERVICE_START_TIMEOUT_MS = 15_000L
        private const val SERVICE_STOP_TIMEOUT_MS = 10_000L
        private const val PLAYBACK_PROGRESS_TIMEOUT_MS = 120_000L
    }
}
