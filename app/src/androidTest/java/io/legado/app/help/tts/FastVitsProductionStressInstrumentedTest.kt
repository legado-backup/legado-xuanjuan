package io.legado.app.help.tts

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class FastVitsProductionStressInstrumentedTest {

    @Test
    fun onePointFiveXRapidShortDialogueUsesProductionModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Production Fast VITS model is missing: ${FastVitsOfflineTts.missingModelFiles(context)}",
            FastVitsOfflineTts.isInstalled(context),
        )

        val loadStartedAt = SystemClock.elapsedRealtime()
        val tts = FastVitsOfflineTts.create(context)
        val modelLoadMs = SystemClock.elapsedRealtime() - loadStartedAt
        try {
            val numSpeakers = tts.numSpeakers()
            val sampleRate = tts.sampleRate()
            assertEquals(FastVitsOfflineTts.NUM_SPEAKERS, numSpeakers)
            assertTrue("sample rate must be positive", sampleRate > 0)

            val warmStartedAt = SystemClock.elapsedRealtime()
            val warm = tts.generate("开始。", FastVitsOfflineTts.NARRATOR_SPEAKER, SPEED)
            val warmElapsedMs = SystemClock.elapsedRealtime() - warmStartedAt
            val warmAudioMs = audioDurationMs(warm.samples.size, warm.sampleRate)
            val warmRtf = rtf(warmElapsedMs, warmAudioMs)
            assertTrue("warm-up generated empty audio", warm.samples.isNotEmpty())
            Log.i(TAG, "warmup elapsedMs=$warmElapsedMs audioMs=$warmAudioMs rtf=$warmRtf")

            var totalSynthMs = 0L
            var totalAudioMs = 0L
            var totalChars = 0L
            var maxRtf = 0f
            stressLines.forEachIndexed { index, text ->
                val role = roles[index % roles.size]
                val sid = FastVitsOfflineTts.speakerFor(role)
                val startedAt = SystemClock.elapsedRealtime()
                val audio = tts.generate(text, sid, SPEED)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val audioMs = audioDurationMs(audio.samples.size, audio.sampleRate)
                val itemRtf = rtf(elapsedMs, audioMs)
                assertTrue("generated empty audio for line $index role=$role", audio.samples.isNotEmpty())
                assertTrue("invalid sample rate for line $index", audio.sampleRate > 0)
                totalSynthMs += elapsedMs
                totalAudioMs += audioMs
                totalChars += text.length
                maxRtf = max(maxRtf, itemRtf)
                Log.i(
                    TAG,
                    "line=$index role=$role sid=$sid textLength=${text.length} " +
                        "elapsedMs=$elapsedMs audioMs=$audioMs rtf=$itemRtf",
                )
            }

            assertTrue("stress probe should produce measurable audio", totalAudioMs > 0L)
            assertTrue("stress probe should measure synthesis time", totalSynthMs > 0L)
            val aggregateRtf = rtf(totalSynthMs, totalAudioMs)
            val charsPerSecond = totalChars * 1000f / totalSynthMs
            val summary =
                "FAST_VITS_PRODUCTION_STRESS_SUMMARY speed=$SPEED threads=2 " +
                    "lines=${stressLines.size} synthMs=$totalSynthMs audioMs=$totalAudioMs " +
                    "rtf=$aggregateRtf maxRtf=$maxRtf chars=$totalChars " +
                    "charsPerSecond=$charsPerSecond sampleRate=$sampleRate " +
                    "numSpeakers=$numSpeakers modelLoadMs=$modelLoadMs"
            Log.i(TAG, summary)
            println(summary)
        } finally {
            tts.release()
        }
    }

    private fun audioDurationMs(sampleCount: Int, sampleRate: Int): Long =
        if (sampleRate > 0) sampleCount * 1000L / sampleRate else 0L

    private fun rtf(elapsedMs: Long, audioMs: Long): Float =
        if (audioMs > 0L) elapsedMs.toFloat() / audioMs else 0f

    companion object {
        private const val TAG = "FastVitsProdStress"
        private const val SPEED = 1.5f

        private val roles = listOf(
            SpeechRole.NARRATOR,
            SpeechRole.MALE,
            SpeechRole.FEMALE,
            SpeechRole.UNKNOWN_DIALOGUE,
        )

        private val stressLines = listOf(
            "爸！",
            "少废话，吃饭！",
            "知道了。",
            "你去哪？",
            "马上回来。",
            "别磨蹭。",
            "等等我！",
            "快点。",
            "你听见没有？",
            "听见了。",
            "门关上。",
            "好。",
            "走了！",
            "回来！",
            "我马上到。",
            "别等我。",
            "为什么？",
            "回来再说。",
            "你确定？",
            "确定。",
        )
    }
}
