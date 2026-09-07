package io.legado.app.help.tts

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.service.KokoroModelInstallService
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class KokoroOfflineTtsInstrumentedTest {

    @Test
    fun synthesizeChineseRolesOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        assumeTrue(
            "manual Kokoro role runtime probe; pass kokoroRoleProbe=1 to run",
            arguments.getString("kokoroRoleProbe") == "1",
        )
        assumeTrue(
            "Kokoro model must be installed before running the role probe: " +
                KokoroOfflineTts.missingModelFiles(context).joinToString(),
            KokoroOfflineTts.isInstalled(context),
        )

        val probes = listOf(
            Triple("narrator", 58, "夜色渐深，山路尽头亮起了一盏灯。"),
            Triple("male", 60, "我们现在出发，天亮之前应该能够赶到。"),
            Triple("female", 3, "她轻声说道，我会在这里等你回来。"),
            Triple("unknown", 5, "门外忽然有人问道，里面有人吗。"),
        )
        val tts = KokoroOfflineTts.create(context)
        try {
            probes.forEach { (role, sid, text) ->
                val audio = tts.generate(text = text, sid = sid, speed = 1.0f)
                assertTrue("$role sample rate must be 24000", audio.sampleRate == 24_000)
                assertTrue("$role generated audio must not be empty", audio.samples.isNotEmpty())
                assertTrue("$role generated audio must be finite", audio.samples.all(Float::isFinite))
                val seconds = audio.samples.size.toDouble() / audio.sampleRate.toDouble()
                assertTrue(
                    "$role generated speech duration should be plausible: $seconds",
                    seconds in 0.2..60.0,
                )
                val peak = audio.samples.maxOf { abs(it) }
                assertTrue("$role generated audio must contain signal: peak=$peak", peak > 1e-5f)
                println(
                    "KOKORO_ROLE_PROBE_PASS role=$role sid=$sid " +
                        "sampleRate=${audio.sampleRate} samples=${audio.samples.size} " +
                        "seconds=$seconds peak=$peak"
                )
            }
        } finally {
            tts.release()
        }
    }

    @Test
    fun synthesizeChineseOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        assumeTrue(
            "manual Kokoro runtime probe; pass kokoroLiveProbe=1 to run",
            arguments.getString("kokoroLiveProbe") == "1",
        )
        if (!KokoroOfflineTts.isInstalled(context) &&
            arguments.getString("kokoroInstallModel") == "1"
        ) {
            println("KOKORO_PROBE_INSTALL_START missing=${KokoroOfflineTts.missingModelFiles(context)}")
            KokoroModelInstallService.start(context)
            val deadline = SystemClock.elapsedRealtime() + 12 * 60_000L
            while (!KokoroOfflineTts.isInstalled(context) &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(1_000L)
            }
            println("KOKORO_PROBE_INSTALL_END missing=${KokoroOfflineTts.missingModelFiles(context)}")
        }
        assumeTrue(
            "Kokoro model must be installed before running the live probe: " +
                KokoroOfflineTts.missingModelFiles(context).joinToString(),
            KokoroOfflineTts.isInstalled(context),
        )

        val tts = KokoroOfflineTts.create(context)
        try {
            val audio = tts.generate(
                text = "夜色渐深，她轻声说道，我们该出发了。",
                sid = KokoroOfflineTts.speakerFor(SpeechRole.NARRATOR),
                speed = 1.0f,
            )
            assertTrue("sample rate must be positive", audio.sampleRate > 0)
            assertTrue("generated audio must not be empty", audio.samples.isNotEmpty())
            val seconds = audio.samples.size.toDouble() / audio.sampleRate.toDouble()
            assertTrue("generated speech duration should be plausible: $seconds", seconds in 0.2..60.0)
            println(
                "KOKORO_PROBE_PASS sampleRate=${audio.sampleRate} " +
                    "samples=${audio.samples.size} seconds=$seconds"
            )
        } finally {
            tts.release()
        }
    }
}
