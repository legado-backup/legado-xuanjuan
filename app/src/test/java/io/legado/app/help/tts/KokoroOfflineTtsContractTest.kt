package io.legado.app.help.tts

import io.legado.app.service.OFFLINE_TTS_PREFETCH_MAX_AHEAD_CHUNKS
import io.legado.app.service.OFFLINE_TTS_STREAM_BUFFER_MS
import io.legado.app.service.OFFLINE_TTS_STREAM_HANDOFF_MS
import io.legado.app.service.OFFLINE_TTS_STREAM_PREFILL_MS
import io.legado.app.service.OFFLINE_TTS_STRESS_PRIME_AHEAD_CHUNKS
import io.legado.app.service.OFFLINE_TTS_STRESS_SPEED
import io.legado.app.service.offlineTtsPreferredPrefetchAheadChunks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KokoroOfflineTtsContractTest {

    @Test
    fun `offline engine is a distinct read aloud service`() {
        val readAloud = source("src/main/java/io/legado/app/model/ReadAloud.kt")
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(readAloud.contains("KokoroOfflineTts.ENGINE_TOKEN"))
        assertTrue(readAloud.contains("OfflineReadAloudService::class.java"))
        assertTrue(manifest.contains(".service.OfflineReadAloudService"))
    }

    @Test
    fun `model stays outside apk and is validated in private files directory`() {
        val helper = source("src/main/java/io/legado/app/help/tts/KokoroOfflineTts.kt")
        assertTrue(helper.contains("File(context.filesDir, \"tts_models\")"))
        assertTrue(helper.contains("model.int8.onnx"))
        assertTrue(helper.contains("voices.bin"))
        assertTrue(helper.contains("espeak-ng-data"))
        assertFalse(helper.contains("context.assets"))
    }

    @Test
    fun `installer downloads once then safely extracts and atomically activates`() {
        val installer = source("src/main/java/io/legado/app/service/KokoroModelInstallService.kt")
        assertTrue(installer.contains("KokoroOfflineTts.MODEL_DOWNLOAD_URL"))
        assertTrue(installer.contains("LibArchiveUtils.unArchive"))
        assertTrue(installer.contains(".install-"))
        assertTrue(installer.contains(".backup-"))
        assertTrue(installer.contains("missingModelFiles"))
    }

    @Test
    fun `speaker ranges match Chinese Kokoro voices and all roles persist`() {
        val helper = source("src/main/java/io/legado/app/help/tts/KokoroOfflineTts.kt")
        val config = source("src/main/java/io/legado/app/help/config/AppConfig.kt")
        assertTrue(helper.contains("FEMALE_SPEAKER_MIN = 3"))
        assertTrue(helper.contains("FEMALE_SPEAKER_MAX = 57"))
        assertTrue(helper.contains("MALE_SPEAKER_MIN = 58"))
        assertTrue(helper.contains("MALE_SPEAKER_MAX = 102"))
        listOf("Narrator", "Male", "Female", "Unknown").forEach {
            assertTrue(config.contains("kokoro${it}Speaker"))
        }
    }

    @Test
    fun `engine picker makes installation status explicit`() {
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt")
        val layout = source("src/main/res/layout/item_offline_tts_engine.xml")
        assertTrue(dialog.contains("ItemOfflineTtsEngineBinding"))
        assertTrue(dialog.contains("KokoroOfflineTts.isInstalled"))
        assertTrue(dialog.contains("KokoroModelInstallService.start"))
        assertTrue(dialog.contains("KokoroVoiceDialog"))
        assertTrue(dialog.contains("ModelInstallPhase.DOWNLOADING"))
        assertTrue(dialog.contains("Formatter.formatShortFileSize"))
        assertTrue(dialog.contains("startOfflineModelInstall(force = true)"))
        assertTrue(dialog.contains("KokoroOfflineTts.deleteModel"))
        assertTrue(layout.contains("@+id/pb_model_progress"))
        assertTrue(layout.contains("@+id/tv_preview"))
        assertTrue(layout.contains("@+id/tv_model_manage"))
    }

    @Test
    fun `installer publishes in app progress and supports safe re download`() {
        val helper = source("src/main/java/io/legado/app/help/tts/KokoroOfflineTts.kt")
        val installer = source("src/main/java/io/legado/app/service/KokoroModelInstallService.kt")
        assertTrue(helper.contains("data class ModelInstallSnapshot"))
        assertTrue(helper.contains("DOWNLOADING,"))
        assertTrue(helper.contains("fun modelSizeBytes(context: Context)"))
        assertTrue(helper.contains("fun deleteModel(context: Context): Boolean"))
        assertTrue(installer.contains("EXTRA_FORCE_INSTALL"))
        assertTrue(installer.contains("startInstall(startId: Int, force: Boolean)"))
        assertTrue(installer.contains("downloadedBytes = copied"))
        assertTrue(installer.contains("publishInstallState"))
    }

    @Test
    fun `apk keeps only sherpa JNI runtime libraries`() {
        val gradle = source("build.gradle")
        assertTrue(gradle.contains("**/libsherpa-onnx-c-api.so"))
        assertTrue(gradle.contains("**/libsherpa-onnx-cxx-api.so"))
        assertTrue(gradle.contains("jniLibs"))
    }

    @Test
    fun `offline playback keeps a bounded session aware pre synthesis window`() {
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
        assertTrue(service.contains("OFFLINE_TTS_PREFETCH_MIN_AHEAD_CHUNKS = 4"))
        assertTrue(service.contains("OFFLINE_TTS_PREFETCH_MAX_AHEAD_CHUNKS = 12"))
        assertTrue(service.contains("OFFLINE_TTS_PREFETCH_TARGET_TEXT_CHARS = 220"))
        assertTrue(
            service.contains(
                "OFFLINE_TTS_PREFETCH_CACHE_SIZE =\n    OFFLINE_TTS_PREFETCH_MAX_AHEAD_CHUNKS + 1"
            )
        )
        assertTrue(service.contains("Channel<SynthesisRequest>(OFFLINE_TTS_PREFETCH_CACHE_SIZE)"))
        assertTrue(service.contains("CompletableDeferred<SynthesizedAudio>"))
        assertTrue(service.contains("buildPrefetchKeys("))
        assertTrue(service.contains("offlineTtsPreferredPrefetchAheadChunks(speed)"))
        assertTrue(service.contains("shouldStopPrefetch(keys.size, futureTextChars, preferredAhead)"))
        assertTrue(service.contains("futureTextChars >= OFFLINE_TTS_PREFETCH_TARGET_TEXT_CHARS"))
        assertTrue(service.contains("OfflineBackend.KOKORO -> KokoroCharacterVoices.speakerFor("))
        assertTrue(service.contains("OfflineBackend.FAST_VITS -> FastVitsOfflineTts.speakerFor(chunk.role)"))
        assertTrue(service.contains("ReadBook.book,"))
        assertTrue(service.contains("chunk.characterName,"))
        assertTrue(service.contains("chunk.role,"))
        assertTrue(service.contains("speed = speed"))
        assertTrue(service.contains("playbackSessionId.incrementAndGet()"))
        assertTrue(service.contains("clearSynthesisPipeline()"))
        assertTrue(service.contains("retainSynthesisWindow(keys)"))
        assertTrue(service.contains("if (request.result.isCompleted) continue"))
        assertTrue(service.contains("synchronized(ttsLock)"))
    }

    @Test
    fun `one point five x keeps a deep runway for rapid short dialogue`() {
        assertTrue(OFFLINE_TTS_PREFETCH_MAX_AHEAD_CHUNKS >= 12)
        assertTrue(offlineTtsPreferredPrefetchAheadChunks(1.0f) >= 4)
        assertTrue(offlineTtsPreferredPrefetchAheadChunks(1.5f) >= 10)
        assertTrue(
            offlineTtsPreferredPrefetchAheadChunks(1.5f) <=
                OFFLINE_TTS_PREFETCH_MAX_AHEAD_CHUNKS
        )
        assertTrue(OFFLINE_TTS_STRESS_SPEED <= 1.5f)
        assertTrue(OFFLINE_TTS_STRESS_PRIME_AHEAD_CHUNKS >= 3)
    }

    @Test
    fun `short clip playback rebuilds runway after a real starvation`() {
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
            .replace("\r\n", "\n")

        assertTrue(service.contains("OFFLINE_TTS_REBUFFER_SHORT_CLIP_MS = 3600"))
        assertTrue(service.contains("OFFLINE_TTS_REBUFFER_WAIT_MS = 2400L"))
        assertTrue(service.contains("private var streamNeedsPriming = true"))
        assertTrue(service.contains("primeShortClipIfNeeded("))
        assertTrue(service.contains("withTimeoutOrNull(OFFLINE_TTS_REBUFFER_WAIT_MS)"))
        assertTrue(service.contains("val starved = nextAudio != null && !nextAudio.isCompleted"))
        assertTrue(service.contains("streamNeedsPriming = starved"))
        assertTrue(service.contains("streamNeedsPriming = false"))

        val prime = service.substringAfter("private suspend fun primeShortClipIfNeeded(")
            .substringBefore("private suspend fun playSamples(")
        assertTrue(prime.contains("for (pending in futureAudios)"))
        assertTrue(prime.contains("pending.await()"))
        assertTrue(prime.contains("streamNeedsPriming = false"))
        assertTrue(prime.contains("val stressMode = playbackMetricsSpeed >= OFFLINE_TTS_STRESS_SPEED"))
        assertTrue(prime.contains("if (!stressMode && durationMs > OFFLINE_TTS_REBUFFER_SHORT_CLIP_MS)"))
        assertFalse(prime.contains("if (durationMs > OFFLINE_TTS_REBUFFER_SHORT_CLIP_MS)"))
        assertFalse(prime.contains("KokoroOfflineTts.withGeneration"))
    }

    @Test
    fun `repeated short dialogue keeps completed pcm until the window advances`() {
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
            .replace("\r\n", "\n")
        val speakLoop = service.substringAfter("private suspend fun speakCurrentParagraph")
            .substringBefore("private fun buildPrefetchKeys")

        assertFalse(speakLoop.contains("consumeSynthesizedAudio(currentKey)"))
        assertTrue(service.contains("retainSynthesisWindow(keys)"))
    }

    @Test
    fun `pause keeps the warm pre synthesis window for quick resume`() {
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
            .replace("\r\n", "\n")
        val pause = service.substringAfter("override fun pauseReadAloud")
            .substringBefore("override fun resumeReadAloud")
        assertFalse(pause.contains("clearSynthesisPipeline()"))
        assertFalse(pause.contains("playStop()"))
    }

    @Test
    fun `offline playback reuses a bounded streaming audio track for smooth handoff`() {
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
            .replace("\r\n", "\n")
        val playSamples = service.substringAfter("private suspend fun playSamples(")
            .substringBefore("private fun getOrCreateStreamingTrack")

        assertTrue(service.contains("OFFLINE_TTS_STREAM_BUFFER_MS = 1800"))
        assertTrue(service.contains("OFFLINE_TTS_STREAM_PREFILL_MS = 700"))
        assertTrue(service.contains("OFFLINE_TTS_STREAM_HANDOFF_MS = 1400"))
        assertTrue(OFFLINE_TTS_STREAM_HANDOFF_MS < OFFLINE_TTS_STREAM_BUFFER_MS)
        assertTrue(OFFLINE_TTS_STREAM_PREFILL_MS >= 500)
        assertTrue(service.contains(".setTransferMode(AudioTrack.MODE_STREAM)"))
        assertFalse(service.contains(".setTransferMode(AudioTrack.MODE_STATIC)"))
        assertTrue(service.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(service.contains("track.setStartThresholdInFrames(startThresholdFrames)"))
        assertTrue(service.contains("framesForMs(sampleRate, OFFLINE_TTS_STREAM_PREFILL_MS)"))
        assertTrue(service.contains("track.bufferCapacityInFrames"))
        assertTrue(service.contains("currentTrackSampleRate == sampleRate"))
        assertTrue(service.contains("streamScheduledFrames += samples.size"))
        assertTrue(service.contains("AudioTrack.WRITE_NON_BLOCKING"))
        assertTrue(playSamples.contains("waitForPlaybackHead(track, chunkStartFrame, sessionId)"))
        assertTrue(
            playSamples.indexOf("waitForPlaybackHead(track, chunkStartFrame, sessionId)") <
                playSamples.indexOf("onStarted()")
        )
        assertFalse(playSamples.contains("hasNextAudio"))
        assertTrue(playSamples.contains("nextAudio: CompletableDeferred<SynthesizedAudio>?"))
        assertTrue(playSamples.contains("waitForReadyHandoff("))
        assertTrue(service.contains("nextAudio.isCompleted && playbackHead >= handoffAtFrame"))
        assertTrue(service.contains("if (playbackHead >= chunkEndFrame) {"))
        assertTrue(service.contains("val starved = nextAudio != null && !nextAudio.isCompleted"))
        assertTrue(service.contains("streamNeedsPriming = starved"))
        assertFalse(playSamples.contains("releaseTrackLocked()"))
        assertFalse(playSamples.contains("track.release()"))
    }

    @Test
    fun `stream track is reset on new sessions but preserved while paused`() {
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
            .replace("\r\n", "\n")
        val playStop = service.substringAfter("override fun playStop()")
            .substringBefore("private fun clearSynthesisPipeline")
        val release = service.substringAfter("private fun releaseTrackLocked()")
            .substringBefore("override fun pauseReadAloud")
        val pause = service.substringAfter("override fun pauseReadAloud")
            .substringBefore("override fun resumeReadAloud")

        assertTrue(playStop.contains("releaseTrackLocked()"))
        assertTrue(release.contains("track.flush()"))
        assertTrue(release.contains("track.release()"))
        assertTrue(release.contains("currentTrackSampleRate = 0"))
        assertTrue(release.contains("streamScheduledFrames = 0L"))
        assertFalse(pause.contains("flush()"))
        assertFalse(pause.contains("release()"))
        assertFalse(pause.contains("streamScheduledFrames = 0L"))
    }

    @Test
    fun `stress playback reports low noise session metrics for real starvation`() {
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
            .replace("\r\n", "\n")

        assertTrue(service.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.N"))
        assertTrue(service.contains("track.underrunCount"))
        assertTrue(service.contains("sessionUnderrunCount"))
        assertTrue(service.contains("sessionStarvationCount"))
        assertTrue(service.contains("sessionRebufferPrimeCount"))
        assertTrue(service.contains("sessionPrimeWaitCount"))
        assertTrue(service.contains("sessionPrimeWaitMs"))
        assertTrue(service.contains("sessionStressHandoffHit"))
        assertTrue(service.contains("sessionStressHandoffMiss"))
        assertTrue(service.contains("sessionSynthCount"))
        assertTrue(service.contains("sessionSynthMs"))
        assertTrue(service.contains("sessionMaxSynthMs"))
        assertTrue(service.contains("sessionSynthTextChars"))
        assertTrue(service.contains("sessionSynthAudioMs"))
        assertTrue(service.contains("sessionMaxSynthRtf"))
        assertTrue(service.contains("val starved = nextAudio != null && !nextAudio.isCompleted"))
        assertTrue(service.contains("playbackMetricsSpeed >= OFFLINE_TTS_STRESS_SPEED"))
        assertTrue(service.contains("SystemClock.elapsedRealtime()"))
        assertTrue(service.contains("val synthStartedAt = SystemClock.elapsedRealtime()"))
        assertTrue(service.contains("elapsedMs >= 800L"))
        assertTrue(service.contains("generated.samples.size * 1000L / generated.sampleRate"))
        assertTrue(service.contains("elapsedMs.toFloat() / audioDurationMs"))
        assertTrue(service.contains("${'$'}{backendLogName()}慢合成 textLength="))
        assertTrue(service.contains("backend=${'$'}{backendLogName()} reason="))
        assertTrue(service.contains("speaker=${'$'}{key.speaker}"))
        assertTrue(service.contains("speed=${'$'}{key.speed} elapsedMs=${'$'}elapsedMs audioMs=${'$'}audioDurationMs"))
        assertTrue(service.contains("rtf=${'$'}synthRtf"))
        assertTrue(service.contains("reportPlaybackMetricsIfNeeded(\"stop\")"))
        assertTrue(service.contains("reportPlaybackMetricsIfNeeded(\"replace\")"))
        assertTrue(service.contains("resetPlaybackMetrics(speechSpeed())"))
        assertTrue(service.contains("离线朗读性能汇总"))
        assertTrue(service.contains("if (BuildConfig.DEBUG) Log.d(\"OfflineTtsPerf\", summary)"))
        assertTrue(service.contains("AppLog.putDebug(summary)"))
        listOf(
            "preferredAhead=", "underrun=", "starvation=", "rebufferPrime=",
            "primeWaitCount=", "primeWaitMs=", "stressHandoffHit=",
            "stressHandoffMiss=", "hitRate=", "synthCount=", "synthMs=",
            "maxSynthMs=", "synthTextChars=", "synthAudioMs=", "avgSynthMs=",
            "charsPerSecond=", "synthRtf=", "maxSynthRtf="
        ).forEach { field -> assertTrue(service.contains(field)) }

        val pause = service.substringAfter("override fun pauseReadAloud")
            .substringBefore("override fun resumeReadAloud")
        assertFalse(pause.contains("resetPlaybackMetrics("))
        assertFalse(pause.contains("reportPlaybackMetricsIfNeeded("))
    }

    @Test
    fun `kokoro model is prewarmed and released after a short idle window`() {
        val helper = source("src/main/java/io/legado/app/help/tts/KokoroOfflineTts.kt")
        val readAloud = source("src/main/java/io/legado/app/model/ReadAloud.kt")
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        val engineDialog = source("src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt")

        assertTrue(helper.contains("WARM_IDLE_RELEASE_MS = 120_000L"))
        assertTrue(helper.contains("private var warmTts: OfflineTts? = null"))
        assertTrue(helper.contains("private var warmLeases = 0"))
        assertTrue(helper.contains("fun prewarm(context: Context)"))
        assertTrue(helper.contains("fun acquire(context: Context): OfflineTts"))
        assertTrue(helper.contains("fun release(tts: OfflineTts)"))
        assertTrue(helper.contains("delay(WARM_IDLE_RELEASE_MS)"))
        assertTrue(readAloud.contains("KokoroOfflineTts.prewarm(context)"))
        assertTrue(dialog.contains("KokoroOfflineTts.prewarm(requireContext())"))
        assertTrue(engineDialog.contains("KokoroOfflineTts.prewarm(requireContext())"))
    }

    @Test
    fun `first offline audio reports concise startup state without blocking controls`() {
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
            .replace("\r\n", "\n")
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")

        assertTrue(service.contains("KokoroOfflineTts.PLAYBACK_STATUS_LOADING_MODEL"))
        assertTrue(service.contains("KokoroOfflineTts.PLAYBACK_STATUS_FIRST_AUDIO"))
        assertTrue(service.contains("FastVitsOfflineTts.PLAYBACK_STATUS_LOADING_MODEL"))
        assertTrue(service.contains("FastVitsOfflineTts.PLAYBACK_STATUS_FIRST_AUDIO"))
        assertTrue(service.contains("delay(850)"))
        assertTrue(service.contains("toastOnUi(playbackPreparingTextRes())"))
        assertTrue(service.contains("OfflineBackend.KOKORO -> R.string.kokoro_playback_preparing"))
        assertTrue(service.contains("OfflineBackend.FAST_VITS -> R.string.fast_vits_playback_preparing"))
        assertTrue(service.contains("markFirstAudioReady()"))
        assertTrue(service.contains("KokoroOfflineTts.acquire(this)"))
        assertTrue(service.contains("KokoroOfflineTts.release(tts)"))
        assertTrue(service.contains("FastVitsOfflineTts.release(tts)"))
        assertTrue(service.contains("postEvent(playbackStatusEvent(), status)"))
        assertTrue(dialog.contains("KokoroOfflineTts.PLAYBACK_STATUS_EVENT"))
        assertTrue(dialog.contains("R.string.kokoro_playback_loading_model"))
        assertTrue(dialog.contains("R.string.kokoro_playback_first_audio"))

        val pause = service.substringAfter("override fun pauseReadAloud")
            .substringBefore("override fun resumeReadAloud")
        assertFalse(pause.contains("KokoroOfflineTts::release"))
        assertFalse(pause.contains("PLAYBACK_STATUS_IDLE"))
    }

    private fun source(path: String): String {
        val file = sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $path" }
        return file.readText()
    }
}
