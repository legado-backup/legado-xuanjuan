package io.legado.app.service

import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.OfflineTts
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.FastVitsOfflineTts
import io.legado.app.help.tts.KokoroCharacterVoices
import io.legado.app.help.tts.KokoroOfflineTts
import io.legado.app.help.tts.NovelSpeechSegmenter
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

internal const val OFFLINE_TTS_PREFETCH_MIN_AHEAD_CHUNKS = 4
internal const val OFFLINE_TTS_PREFETCH_MAX_AHEAD_CHUNKS = 12
internal const val OFFLINE_TTS_PREFETCH_TARGET_TEXT_CHARS = 220
internal const val OFFLINE_TTS_PREFETCH_CACHE_SIZE =
    OFFLINE_TTS_PREFETCH_MAX_AHEAD_CHUNKS + 1
internal const val OFFLINE_TTS_STRESS_SPEED = 1.45f
internal const val OFFLINE_TTS_STRESS_PRIME_AHEAD_CHUNKS = 3
// Keep a small but meaningful PCM runway so native Kokoro inference or coroutine scheduling
// jitter on slower devices does not starve the streaming AudioTrack between role/chunk changes.
internal const val OFFLINE_TTS_STREAM_BUFFER_MS = 1800
internal const val OFFLINE_TTS_STREAM_PREFILL_MS = 700
internal const val OFFLINE_TTS_STREAM_HANDOFF_MS = 1400
internal const val OFFLINE_TTS_REBUFFER_SHORT_CLIP_MS = 3600
internal const val OFFLINE_TTS_REBUFFER_WAIT_MS = 2400L
private const val OFFLINE_TTS_STREAM_WRITE_SLICE_MS = 80

internal fun offlineTtsPreferredPrefetchAheadChunks(speed: Float): Int {
    // Long prose still stops early at the text-character target. Short dialogue does not,
    // so 1.5x can build about ten chunks of runway without making narration PCM unbounded.
    val speedBoost = ((speed - 1f).coerceAtLeast(0f) * 12f).roundToInt()
    return (OFFLINE_TTS_PREFETCH_MIN_AHEAD_CHUNKS + speedBoost).coerceIn(
        OFFLINE_TTS_PREFETCH_MIN_AHEAD_CHUNKS,
        OFFLINE_TTS_PREFETCH_MAX_AHEAD_CHUNKS,
    )
}

/**
 * Fully local read-aloud service backed by sherpa-onnx local neural engines.
 * Model files live in app-private storage and are never bundled in the APK.
 */
class OfflineReadAloudService : BaseReadAloudService() {

    private enum class OfflineBackend {
        KOKORO,
        FAST_VITS,
    }

    private data class AudioKey(
        val text: String,
        val speaker: Int,
        val speed: Float,
    )

    private data class SynthesizedAudio(
        val samples: FloatArray,
        val sampleRate: Int,
    )

    private data class SynthesisRequest(
        val sessionId: Long,
        val key: AudioKey,
        val result: CompletableDeferred<SynthesizedAudio>,
    )

    private val playbackSessionId = AtomicLong()
    private val ttsLock = Any()
    private val trackLock = Any()
    private val synthesisCacheLock = Any()
    private var offlineTts: OfflineTts? = null
    private var offlineTtsBackend: OfflineBackend? = null
    private var activeBackend = OfflineBackend.KOKORO
    private var playbackJob: Job? = null
    private var synthesisWorkerJob: Job? = null
    private var synthesisRequests: Channel<SynthesisRequest>? = null
    private val synthesisCache =
        LinkedHashMap<AudioKey, CompletableDeferred<SynthesizedAudio>>()
    private var currentTrack: AudioTrack? = null
    private var currentTrackSampleRate: Int = 0
    private var streamScheduledFrames: Long = 0L
    private var playbackMetricsActive = false
    private var playbackMetricsSpeed = 1f
    private var sessionUnderrunCount = 0
    private var lastTrackUnderrunCount = 0
    private var sessionStarvationCount = 0
    private var sessionInitialPrimeCount = 0
    private var sessionRebufferPrimeCount = 0
    private var sessionPrimeWaitCount = 0
    private var sessionPrimeWaitMs = 0L
    private var sessionStressHandoffHit = 0
    private var sessionStressHandoffMiss = 0
    private var sessionSynthCount = 0
    private var sessionSynthMs = 0L
    private var sessionMaxSynthMs = 0L
    private var sessionSynthTextChars = 0L
    private var sessionSynthAudioMs = 0L
    private var sessionMaxSynthRtf = 0f
    @Volatile
    private var streamNeedsPriming = true
    private var startupHintJob: Job? = null
    @Volatile
    private var firstAudioStarted = false
    @Volatile
    private var startupStatus = -1

    override fun onDestroy() {
        playStop()
        synchronized(ttsLock) {
            releaseOfflineTtsLocked()
        }
        super.onDestroy()
    }

    @Synchronized
    override fun play() {
        val requestedBackend = resolveOfflineBackend()
        if (!isBackendInstalled(requestedBackend)) {
            toastOnUi("离线朗读模型尚未安装")
            stopSelf()
            return
        }
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("离线朗读列表为空")
            ReadBook.readAloud()
            return
        }
        reportPlaybackMetricsIfNeeded("replace")
        val sessionId = playbackSessionId.incrementAndGet()
        playbackJob?.cancel()
        clearSynthesisPipeline()
        synchronized(trackLock) { releaseTrackLocked() }
        prepareBackendForSession(requestedBackend)
        firstAudioStarted = false
        resetPlaybackMetrics(speechSpeed())
        setStartupStatus(playbackStatusLoadingModel())
        startupHintJob?.cancel()
        startupHintJob = lifecycleScope.launch(Main) {
            delay(850)
            if (isCurrentSession(sessionId) && !firstAudioStarted && !pause) {
                toastOnUi(playbackPreparingTextRes())
            }
        }
        super.play()
        startSynthesisWorker(sessionId)
        playbackJob = lifecycleScope.launch(IO) {
            try {
                speakCurrentParagraph(sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (sessionId == playbackSessionId.get()) {
                    AppLog.put("离线朗读出错\n${error.localizedMessage}", error, true)
                    withContext(Main.immediate) {
                        toastOnUi("离线朗读失败: ${error.localizedMessage ?: "未知错误"}")
                        pauseReadAloud()
                    }
                }
            }
        }
    }

    private suspend fun speakCurrentParagraph(sessionId: Long) {
        while (isCurrentSession(sessionId)) {
            currentCoroutineContext().ensureActive()
            if (nowSpeak !in contentList.indices) return
            val paragraph = contentList[nowSpeak]
            if (paragraph.matches(AppPattern.notReadAloudRegex)) {
                if (!advanceParagraph(sessionId)) return
                continue
            }
            val startParagraphPos = paragraphStartPos.coerceIn(0, paragraph.length)
            val speakText = paragraph.substring(startParagraphPos)
            val chunks = NovelSpeechSegmenter.chunk(speakText)
            if (chunks.isEmpty()) {
                if (!advanceParagraph(sessionId)) return
                continue
            }
            val speed = speechSpeed()
            for ((chunkIndex, chunk) in chunks.withIndex()) {
                currentCoroutineContext().ensureActive()
                ensureSession(sessionId)
                val prefetchKeys = buildPrefetchKeys(
                    paragraphIndex = nowSpeak,
                    paragraphStartPos = startParagraphPos,
                    currentChunkIndex = chunkIndex,
                    speed = speed,
                )
                queuePrefetchWindow(prefetchKeys, sessionId)
                waitWhilePaused(sessionId)
                val currentKey = prefetchKeys.first()
                val primeAheadCount = if (speed >= OFFLINE_TTS_STRESS_SPEED) {
                    OFFLINE_TTS_STRESS_PRIME_AHEAD_CHUNKS
                } else {
                    1
                }
                val futureAudios = prefetchKeys
                    .drop(1)
                    .take(primeAheadCount)
                    .map { nextKey -> ensureSynthesisQueued(nextKey, sessionId) }
                val nextAudio = futureAudios.firstOrNull()
                val absoluteProgress = readAloudNumber + chunk.start
                val audio = awaitSynthesizedAudio(currentKey, sessionId)
                ensureSession(sessionId)
                if (audio.samples.isNotEmpty()) {
                    primeShortClipIfNeeded(
                        audio = audio,
                        futureAudios = futureAudios,
                        sessionId = sessionId,
                    )
                    playSamples(
                        samples = audio.samples,
                        sampleRate = audio.sampleRate,
                        sessionId = sessionId,
                        nextAudio = nextAudio,
                    ) {
                        markFirstAudioReady()
                        moveToSpeechPage(absoluteProgress)
                        upTtsProgress(absoluteProgress + 1)
                    }
                } else {
                    markFirstAudioReady()
                    moveToSpeechPage(absoluteProgress)
                    upTtsProgress(absoluteProgress + 1)
                }
                // Let the next retainSynthesisWindow() decide eviction. This intentionally
                // keeps completed PCM reusable for repeated tiny lines from the same speaker.
            }
            if (!advanceParagraph(sessionId)) return
        }
    }

    private fun buildPrefetchKeys(
        paragraphIndex: Int,
        paragraphStartPos: Int,
        currentChunkIndex: Int,
        speed: Float,
    ): List<AudioKey> {
        val keys = ArrayList<AudioKey>(OFFLINE_TTS_PREFETCH_CACHE_SIZE)
        val preferredAhead = offlineTtsPreferredPrefetchAheadChunks(speed)
        var futureTextChars = 0
        var index = paragraphIndex
        paragraphLoop@ while (
            index in contentList.indices && keys.size < OFFLINE_TTS_PREFETCH_CACHE_SIZE
        ) {
            val paragraph = contentList[index]
            if (!paragraph.matches(AppPattern.notReadAloudRegex)) {
                val startPos = if (index == paragraphIndex) {
                    paragraphStartPos.coerceIn(0, paragraph.length)
                } else {
                    0
                }
                val chunks = NovelSpeechSegmenter.chunk(paragraph.substring(startPos))
                val firstChunk = if (index == paragraphIndex) currentChunkIndex else 0
                for (chunkIndex in firstChunk until chunks.size) {
                    val chunk = chunks[chunkIndex]
                    keys += AudioKey(
                        text = chunk.text,
                        speaker = when (activeBackend) {
                            OfflineBackend.KOKORO -> KokoroCharacterVoices.speakerFor(
                                ReadBook.book,
                                chunk.characterName,
                                chunk.role,
                            )
                            OfflineBackend.FAST_VITS -> FastVitsOfflineTts.speakerFor(chunk.role)
                        },
                        speed = speed,
                    )
                    if (keys.size > 1) {
                        futureTextChars += chunk.text.length
                    }
                    if (shouldStopPrefetch(keys.size, futureTextChars, preferredAhead)) {
                        break@paragraphLoop
                    }
                }
            }
            index++
        }
        return keys
    }

    private fun shouldStopPrefetch(
        keyCount: Int,
        futureTextChars: Int,
        preferredAhead: Int,
    ): Boolean {
        if (keyCount >= OFFLINE_TTS_PREFETCH_CACHE_SIZE) return true
        val ahead = (keyCount - 1).coerceAtLeast(0)
        if (ahead < OFFLINE_TTS_PREFETCH_MIN_AHEAD_CHUNKS) return false
        return futureTextChars >= OFFLINE_TTS_PREFETCH_TARGET_TEXT_CHARS ||
            ahead >= preferredAhead
    }

    private fun queuePrefetchWindow(keys: List<AudioKey>, sessionId: Long) {
        retainSynthesisWindow(keys)
        keys.forEach { key -> ensureSynthesisQueued(key, sessionId) }
    }

    private fun retainSynthesisWindow(keys: List<AudioKey>) {
        val keep = keys.toHashSet()
        synchronized(synthesisCacheLock) {
            val iterator = synthesisCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in keep) {
                    if (!entry.value.isCompleted) {
                        entry.value.cancel(
                            CancellationException("offline synthesis window advanced")
                        )
                    }
                    iterator.remove()
                }
            }
        }
    }

    private fun ensureSynthesisQueued(
        key: AudioKey,
        sessionId: Long,
    ): CompletableDeferred<SynthesizedAudio> {
        ensureSession(sessionId)
        var request: SynthesisRequest? = null
        val deferred = synchronized(synthesisCacheLock) {
            synthesisCache[key] ?: CompletableDeferred<SynthesizedAudio>().also { created ->
                synthesisCache[key] = created
                request = SynthesisRequest(sessionId, key, created)
                trimSynthesisCacheLocked()
            }
        }
        request?.let { pending ->
            val result = synthesisRequests?.trySend(pending)
            if (result == null || result.isFailure) {
                synchronized(synthesisCacheLock) {
                    if (synthesisCache[key] === deferred) synthesisCache.remove(key)
                }
                deferred.completeExceptionally(
                    IllegalStateException("offline synthesis queue is unavailable")
                )
            }
        }
        return deferred
    }

    private suspend fun awaitSynthesizedAudio(
        key: AudioKey,
        sessionId: Long,
    ): SynthesizedAudio {
        val prefetched = ensureSynthesisQueued(key, sessionId)
        return try {
            prefetched.await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ensureSession(sessionId)
            AppLog.putDebug(
                "离线朗读预合成失败，当前片段改为即时重试: ${error.localizedMessage}"
            )
            synthesizeNow(key, sessionId)
        }
    }

    private fun trimSynthesisCacheLocked() {
        while (synthesisCache.size > OFFLINE_TTS_PREFETCH_CACHE_SIZE) {
            val removable = synthesisCache.entries.firstOrNull { it.value.isCompleted } ?: break
            synthesisCache.remove(removable.key)
        }
    }

    private fun startSynthesisWorker(sessionId: Long) {
        val requests = Channel<SynthesisRequest>(OFFLINE_TTS_PREFETCH_CACHE_SIZE)
        synthesisRequests = requests
        synthesisWorkerJob = lifecycleScope.launch(IO) {
            for (request in requests) {
                if (request.result.isCompleted) continue
                if (!isCurrentSession(sessionId) || request.sessionId != sessionId) {
                    request.result.cancel(
                        CancellationException("offline playback session changed")
                    )
                    continue
                }
                try {
                    request.result.complete(synthesizeNow(request.key, sessionId))
                } catch (error: CancellationException) {
                    request.result.cancel(error)
                    throw error
                } catch (error: Throwable) {
                    request.result.completeExceptionally(error)
                }
            }
        }
    }

    private fun synthesizeNow(key: AudioKey, sessionId: Long): SynthesizedAudio {
        ensureSession(sessionId)
        val audio = synchronized(ttsLock) {
            ensureSession(sessionId)
            val tts = acquireOfflineTtsLocked()
            if (!firstAudioStarted) {
                setStartupStatus(playbackStatusFirstAudio())
            }
            val synthStartedAt = SystemClock.elapsedRealtime()
            val generated = withBackendGeneration {
                tts.generate(
                    text = key.text,
                    sid = key.speaker,
                    speed = key.speed,
                )
            }
            val elapsedMs = (SystemClock.elapsedRealtime() - synthStartedAt).coerceAtLeast(0L)
            val audioDurationMs = if (generated.sampleRate > 0) {
                generated.samples.size * 1000L / generated.sampleRate
            } else {
                0L
            }
            val synthRtf = if (audioDurationMs > 0L) {
                elapsedMs.toFloat() / audioDurationMs
            } else {
                0f
            }
            if (playbackMetricsActive && isCurrentSession(sessionId)) {
                sessionSynthCount++
                sessionSynthMs += elapsedMs
                sessionMaxSynthMs = max(sessionMaxSynthMs, elapsedMs)
                sessionSynthTextChars += key.text.length
                sessionSynthAudioMs += audioDurationMs
                sessionMaxSynthRtf = max(sessionMaxSynthRtf, synthRtf)
            }
            if (BuildConfig.DEBUG && elapsedMs >= 800L) {
                Log.d(
                    "OfflineTtsPerf",
                    "${backendLogName()}慢合成 textLength=${key.text.length} speaker=${key.speaker} " +
                        "speed=${key.speed} elapsedMs=$elapsedMs audioMs=$audioDurationMs " +
                        "rtf=$synthRtf"
                )
            }
            generated
        }
        ensureSession(sessionId)
        return SynthesizedAudio(audio.samples, audio.sampleRate)
    }

    private fun speechSpeed(): Float {
        return ((AppConfig.kokoroSpeechRate + 5) / 10f).coerceIn(0.5f, 3.0f)
    }

    private suspend fun primeShortClipIfNeeded(
        audio: SynthesizedAudio,
        futureAudios: List<CompletableDeferred<SynthesizedAudio>>,
        sessionId: Long,
    ) {
        if (!streamNeedsPriming || futureAudios.isEmpty() || audio.sampleRate <= 0) return
        val durationMs = audio.samples.size * 1000L / audio.sampleRate
        val stressMode = playbackMetricsSpeed >= OFFLINE_TTS_STRESS_SPEED
        if (!stressMode && durationMs > OFFLINE_TTS_REBUFFER_SHORT_CLIP_MS) {
            streamNeedsPriming = false
            return
        }
        val isRebufferPrime = firstAudioStarted
        val waitStartedAt = SystemClock.elapsedRealtime()
        val targetCount = futureAudios.size
        val primedCount = withTimeoutOrNull(OFFLINE_TTS_REBUFFER_WAIT_MS) {
            var ready = 0
            for (pending in futureAudios) {
                try {
                    pending.await()
                    ready++
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    break
                }
            }
            ready
        } ?: 0
        val waitMs = (SystemClock.elapsedRealtime() - waitStartedAt).coerceAtLeast(0L)
        sessionPrimeWaitCount++
        sessionPrimeWaitMs += waitMs
        if (isRebufferPrime) {
            sessionRebufferPrimeCount++
        } else {
            sessionInitialPrimeCount++
        }
        ensureSession(sessionId)
        // Do one bounded runway rebuild. Only a later real AudioTrack starvation is allowed
        // to arm priming again, avoiding a synthetic wait in front of every tiny dialogue.
        streamNeedsPriming = false
        if (primedCount < targetCount) {
            AppLog.putDebug(
                "离线朗读短对白预缓冲 ${primedCount}/${targetCount}，继续播放并由流式队列接管"
            )
        }
    }

    private suspend fun playSamples(
        samples: FloatArray,
        sampleRate: Int,
        sessionId: Long,
        nextAudio: CompletableDeferred<SynthesizedAudio>?,
        onStarted: suspend () -> Unit,
    ) {
        val track = getOrCreateStreamingTrack(sampleRate, sessionId)
        val chunkStartFrame = synchronized(trackLock) {
            ensureSession(sessionId)
            check(currentTrack === track) { "offline AudioTrack changed unexpectedly" }
            val start = streamScheduledFrames
            streamScheduledFrames += samples.size
            start
        }
        val chunkEndFrame = chunkStartFrame + samples.size
        val prefillFrames = minOf(
            samples.size,
            framesForMs(sampleRate, OFFLINE_TTS_STREAM_PREFILL_MS).toInt(),
        )

        var offset = writeStreamingRange(
            track = track,
            samples = samples,
            startOffset = 0,
            endOffset = prefillFrames,
            sampleRate = sampleRate,
            sessionId = sessionId,
            allowPlaybackStart = false,
        )
        ensureTrackPlaying(track, sessionId)
        waitForPlaybackHead(track, chunkStartFrame, sessionId)
        onStarted()

        offset = writeStreamingRange(
            track = track,
            samples = samples,
            startOffset = offset,
            endOffset = samples.size,
            sampleRate = sampleRate,
            sessionId = sessionId,
            allowPlaybackStart = true,
        )
        check(offset == samples.size) { "AudioTrack 写入不完整: $offset/${samples.size}" }

        waitForReadyHandoff(
            track = track,
            handoffAtFrame = (chunkEndFrame -
                framesForMs(sampleRate, OFFLINE_TTS_STREAM_HANDOFF_MS))
                .coerceAtLeast(chunkStartFrame),
            chunkEndFrame = chunkEndFrame,
            nextAudio = nextAudio,
            sessionId = sessionId,
        )
    }

    /**
     * Give the synthesizer the whole remaining chunk when the next PCM is not ready yet.
     * Once we enter the configured handoff window, return as soon as the next chunk is
     * synthesized so the caller can enqueue it into the same AudioTrack. With no next
     * chunk (or a slow next synthesis), drain the current chunk to its real boundary.
     */
    private suspend fun waitForReadyHandoff(
        track: AudioTrack,
        handoffAtFrame: Long,
        chunkEndFrame: Long,
        nextAudio: CompletableDeferred<SynthesizedAudio>?,
        sessionId: Long,
    ) {
        while (currentCoroutineContext().isActive && isCurrentSession(sessionId)) {
            if (pause) {
                synchronized(trackLock) {
                    if (currentTrack === track && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                }
                delay(20)
                continue
            }
            ensureTrackPlaying(track, sessionId)
            val playbackHead = playbackHeadFrames(track)
            if (playbackHead >= chunkEndFrame) {
                val starved = nextAudio != null && !nextAudio.isCompleted
                streamNeedsPriming = starved
                if (starved) {
                    sessionStarvationCount++
                    if (playbackMetricsSpeed >= OFFLINE_TTS_STRESS_SPEED) {
                        sessionStressHandoffMiss++
                    }
                }
                break
            }
            if (nextAudio != null && nextAudio.isCompleted && playbackHead >= handoffAtFrame) {
                streamNeedsPriming = false
                if (playbackMetricsSpeed >= OFFLINE_TTS_STRESS_SPEED) {
                    sessionStressHandoffHit++
                }
                break
            }
            delay(8)
        }
        ensureSession(sessionId)
    }

    private fun getOrCreateStreamingTrack(sampleRate: Int, sessionId: Long): AudioTrack {
        synchronized(trackLock) {
            ensureSession(sessionId)
            currentTrack?.let { track ->
                if (currentTrackSampleRate == sampleRate &&
                    track.state == AudioTrack.STATE_INITIALIZED
                ) {
                    return track
                }
                releaseTrackLocked()
            }

            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            ).coerceAtLeast(0)
            val streamBufferBytes = framesForMs(
                sampleRate,
                OFFLINE_TTS_STREAM_BUFFER_MS,
            ).toInt() * Float.SIZE_BYTES
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuffer, streamBufferBytes))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                "AudioTrack 初始化失败"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ may default a streaming track's start threshold to the
                // whole buffer capacity. We intentionally prefill only a shorter
                // runway for low first-audio latency; leaving the default threshold
                // can deadlock tiny-dialogue playback because the next chunk waits
                // for the playback head before enough PCM has been queued to start.
                val startThresholdFrames = minOf(
                    framesForMs(sampleRate, OFFLINE_TTS_STREAM_PREFILL_MS).toInt(),
                    track.bufferCapacityInFrames,
                ).coerceAtLeast(1)
                track.setStartThresholdInFrames(startThresholdFrames)
            }
            currentTrack = track
            currentTrackSampleRate = sampleRate
            streamScheduledFrames = 0L
            lastTrackUnderrunCount = readTrackUnderrunCount(track)
            return track
        }
    }

    private suspend fun writeStreamingRange(
        track: AudioTrack,
        samples: FloatArray,
        startOffset: Int,
        endOffset: Int,
        sampleRate: Int,
        sessionId: Long,
        allowPlaybackStart: Boolean,
    ): Int {
        var offset = startOffset
        val writeSliceFrames = max(
            1,
            framesForMs(sampleRate, OFFLINE_TTS_STREAM_WRITE_SLICE_MS).toInt(),
        )
        while (offset < endOffset) {
            currentCoroutineContext().ensureActive()
            ensureSession(sessionId)
            waitWhilePaused(sessionId)
            if (allowPlaybackStart) ensureTrackPlaying(track, sessionId)
            val writeSize = minOf(writeSliceFrames, endOffset - offset)
            val written = track.write(
                samples,
                offset,
                writeSize,
                AudioTrack.WRITE_NON_BLOCKING,
            )
            when {
                written > 0 -> offset += written
                written == 0 -> {
                    ensureTrackPlaying(track, sessionId)
                    delay(4)
                }
                else -> error("AudioTrack 写入失败: $written")
            }
        }
        return offset
    }

    private suspend fun waitForPlaybackHead(
        track: AudioTrack,
        targetFrame: Long,
        sessionId: Long,
    ) {
        while (currentCoroutineContext().isActive && isCurrentSession(sessionId)) {
            if (pause) {
                synchronized(trackLock) {
                    if (currentTrack === track && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                }
                delay(20)
                continue
            }
            ensureTrackPlaying(track, sessionId)
            if (playbackHeadFrames(track) >= targetFrame) break
            delay(8)
        }
        ensureSession(sessionId)
    }

    private fun ensureTrackPlaying(track: AudioTrack, sessionId: Long) {
        synchronized(trackLock) {
            ensureSession(sessionId)
            if (currentTrack !== track) {
                throw CancellationException("offline AudioTrack changed")
            }
            if (!pause && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
        }
    }

    private fun playbackHeadFrames(track: AudioTrack): Long =
        track.playbackHeadPosition.toLong() and 0xffffffffL

    private fun framesForMs(sampleRate: Int, durationMs: Int): Long =
        sampleRate.toLong() * durationMs / 1000L

    private suspend fun waitWhilePaused(sessionId: Long) {
        while (pause && isCurrentSession(sessionId)) {
            currentCoroutineContext().ensureActive()
            delay(40)
        }
        ensureSession(sessionId)
    }

    private suspend fun advanceParagraph(sessionId: Long): Boolean = withContext(Main.immediate) {
        if (!isCurrentSession(sessionId)) return@withContext false
        do {
            readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            if (nowSpeak >= contentList.size) {
                nextChapter(auto = true)
                return@withContext false
            }
        } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        true
    }

    private suspend fun moveToSpeechPage(position: Int) = withContext(Main.immediate) {
        val targetPageIndex = textChapter?.getPageIndexByCharIndex(position) ?: return@withContext
        while (pageIndex < targetPageIndex) {
            pageIndex++
            ReadBook.moveToNextPage(syncReadAloudFollow = true)
        }
    }

    @Synchronized
    override fun playStop() {
        reportPlaybackMetricsIfNeeded("stop")
        playbackSessionId.incrementAndGet()
        startupHintJob?.cancel()
        startupHintJob = null
        firstAudioStarted = false
        setStartupStatus(playbackStatusIdle())
        playbackJob?.cancel()
        playbackJob = null
        clearSynthesisPipeline()
        synchronized(trackLock) { releaseTrackLocked() }
    }

    private fun clearSynthesisPipeline() {
        synthesisWorkerJob?.cancel()
        synthesisWorkerJob = null
        synthesisRequests?.close()
        synthesisRequests = null
        synchronized(synthesisCacheLock) {
            synthesisCache.values.forEach { deferred ->
                if (!deferred.isCompleted) {
                    deferred.cancel(
                        CancellationException("offline synthesis cache cleared")
                    )
                }
            }
            synthesisCache.clear()
        }
    }

    private fun releaseTrackLocked() {
        currentTrack?.let { track ->
            snapshotTrackUnderrunsLocked(track)
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        currentTrack = null
        currentTrackSampleRate = 0
        streamScheduledFrames = 0L
        lastTrackUnderrunCount = 0
        streamNeedsPriming = true
    }

    private fun resetPlaybackMetrics(speed: Float) {
        playbackMetricsActive = true
        playbackMetricsSpeed = speed
        sessionUnderrunCount = 0
        lastTrackUnderrunCount = 0
        sessionStarvationCount = 0
        sessionInitialPrimeCount = 0
        sessionRebufferPrimeCount = 0
        sessionPrimeWaitCount = 0
        sessionPrimeWaitMs = 0L
        sessionStressHandoffHit = 0
        sessionStressHandoffMiss = 0
        sessionSynthCount = 0
        sessionSynthMs = 0L
        sessionMaxSynthMs = 0L
        sessionSynthTextChars = 0L
        sessionSynthAudioMs = 0L
        sessionMaxSynthRtf = 0f
    }

    private fun reportPlaybackMetricsIfNeeded(reason: String) {
        if (!playbackMetricsActive) return
        synchronized(trackLock) {
            currentTrack?.let(::snapshotTrackUnderrunsLocked)
        }
        val stressHandoffTotal = sessionStressHandoffHit + sessionStressHandoffMiss
        val hitRate = if (stressHandoffTotal > 0) {
            sessionStressHandoffHit * 1000L / stressHandoffTotal / 10f
        } else {
            -1f
        }
        val avgSynthMs = if (sessionSynthCount > 0) {
            sessionSynthMs.toFloat() / sessionSynthCount
        } else {
            0f
        }
        val synthCharsPerSecond = if (sessionSynthMs > 0L) {
            sessionSynthTextChars * 1000f / sessionSynthMs
        } else {
            0f
        }
        val synthRtf = if (sessionSynthAudioMs > 0L) {
            sessionSynthMs.toFloat() / sessionSynthAudioMs
        } else {
            0f
        }
        val summary =
            "离线朗读性能汇总 backend=${backendLogName()} reason=$reason speed=$playbackMetricsSpeed " +
                "preferredAhead=${offlineTtsPreferredPrefetchAheadChunks(playbackMetricsSpeed)} " +
                "underrun=$sessionUnderrunCount starvation=$sessionStarvationCount " +
                "initialPrime=$sessionInitialPrimeCount rebufferPrime=$sessionRebufferPrimeCount " +
                "primeWaitCount=$sessionPrimeWaitCount primeWaitMs=$sessionPrimeWaitMs " +
                "stressHandoffHit=$sessionStressHandoffHit " +
                "stressHandoffMiss=$sessionStressHandoffMiss hitRate=$hitRate " +
                "synthCount=$sessionSynthCount synthMs=$sessionSynthMs " +
                "maxSynthMs=$sessionMaxSynthMs synthTextChars=$sessionSynthTextChars " +
                "synthAudioMs=$sessionSynthAudioMs avgSynthMs=$avgSynthMs " +
                "charsPerSecond=$synthCharsPerSecond synthRtf=$synthRtf " +
                "maxSynthRtf=$sessionMaxSynthRtf"
        if (BuildConfig.DEBUG) Log.d("OfflineTtsPerf", summary)
        AppLog.putDebug(summary)
        playbackMetricsActive = false
    }

    private fun snapshotTrackUnderrunsLocked(track: AudioTrack) {
        if (!playbackMetricsActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val currentUnderrunCount = readTrackUnderrunCount(track)
        if (currentUnderrunCount >= lastTrackUnderrunCount) {
            sessionUnderrunCount += currentUnderrunCount - lastTrackUnderrunCount
        }
        lastTrackUnderrunCount = currentUnderrunCount
    }

    private fun readTrackUnderrunCount(track: AudioTrack): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0
        return runCatching { track.underrunCount }.getOrDefault(0)
    }

    private fun markFirstAudioReady() {
        if (firstAudioStarted) return
        firstAudioStarted = true
        startupHintJob?.cancel()
        startupHintJob = null
        setStartupStatus(playbackStatusIdle())
    }

    private fun setStartupStatus(status: Int) {
        if (startupStatus == status) return
        startupStatus = status
        postEvent(playbackStatusEvent(), status)
    }

    private fun resolveOfflineBackend(): OfflineBackend = when (ReadAloud.ttsEngine) {
        FastVitsOfflineTts.ENGINE_TOKEN -> OfflineBackend.FAST_VITS
        else -> OfflineBackend.KOKORO
    }

    private fun isBackendInstalled(backend: OfflineBackend): Boolean = when (backend) {
        OfflineBackend.KOKORO -> KokoroOfflineTts.isInstalled(this)
        OfflineBackend.FAST_VITS -> FastVitsOfflineTts.isInstalled(this)
    }

    private fun prepareBackendForSession(backend: OfflineBackend) {
        synchronized(ttsLock) {
            if (activeBackend != backend) {
                if (startupStatus != playbackStatusIdle()) {
                    postEvent(playbackStatusEvent(), playbackStatusIdle())
                }
                releaseOfflineTtsLocked()
                activeBackend = backend
            } else if (offlineTts != null && offlineTtsBackend != backend) {
                releaseOfflineTtsLocked()
            }
            // Force a fresh status publication for every playback session, even if a
            // previous session was still in the same numeric startup state.
            startupStatus = -1
        }
    }

    private fun acquireOfflineTtsLocked(): OfflineTts {
        offlineTts?.let { existing ->
            check(offlineTtsBackend == activeBackend) {
                "offline TTS backend lease does not match active playback backend"
            }
            return existing
        }
        val backend = activeBackend
        val tts = when (backend) {
            OfflineBackend.KOKORO -> KokoroOfflineTts.acquire(this)
            OfflineBackend.FAST_VITS -> FastVitsOfflineTts.acquire(this)
        }
        offlineTts = tts
        offlineTtsBackend = backend
        return tts
    }

    private fun releaseOfflineTtsLocked() {
        val tts = offlineTts ?: run {
            offlineTtsBackend = null
            return
        }
        when (offlineTtsBackend ?: activeBackend) {
            OfflineBackend.KOKORO -> KokoroOfflineTts.release(tts)
            OfflineBackend.FAST_VITS -> FastVitsOfflineTts.release(tts)
        }
        offlineTts = null
        offlineTtsBackend = null
    }

    private fun <T> withBackendGeneration(block: () -> T): T = when (activeBackend) {
        OfflineBackend.KOKORO -> KokoroOfflineTts.withGeneration(block)
        OfflineBackend.FAST_VITS -> FastVitsOfflineTts.withGeneration(block)
    }

    private fun playbackStatusEvent(): String = when (activeBackend) {
        OfflineBackend.KOKORO -> KokoroOfflineTts.PLAYBACK_STATUS_EVENT
        OfflineBackend.FAST_VITS -> FastVitsOfflineTts.PLAYBACK_STATUS_EVENT
    }

    private fun playbackStatusIdle(): Int = when (activeBackend) {
        OfflineBackend.KOKORO -> KokoroOfflineTts.PLAYBACK_STATUS_IDLE
        OfflineBackend.FAST_VITS -> FastVitsOfflineTts.PLAYBACK_STATUS_IDLE
    }

    private fun playbackStatusLoadingModel(): Int = when (activeBackend) {
        OfflineBackend.KOKORO -> KokoroOfflineTts.PLAYBACK_STATUS_LOADING_MODEL
        OfflineBackend.FAST_VITS -> FastVitsOfflineTts.PLAYBACK_STATUS_LOADING_MODEL
    }

    private fun playbackStatusFirstAudio(): Int = when (activeBackend) {
        OfflineBackend.KOKORO -> KokoroOfflineTts.PLAYBACK_STATUS_FIRST_AUDIO
        OfflineBackend.FAST_VITS -> FastVitsOfflineTts.PLAYBACK_STATUS_FIRST_AUDIO
    }

    private fun playbackPreparingTextRes(): Int = when (activeBackend) {
        OfflineBackend.KOKORO -> R.string.kokoro_playback_preparing
        OfflineBackend.FAST_VITS -> R.string.fast_vits_playback_preparing
    }

    private fun backendLogName(): String = when (activeBackend) {
        OfflineBackend.KOKORO -> "Kokoro"
        OfflineBackend.FAST_VITS -> "FastVITS"
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        synchronized(trackLock) {
            currentTrack?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() }
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        val jobActive = playbackJob?.isActive == true
        synchronized(trackLock) {
            currentTrack?.runCatching { play() }
        }
        if (!jobActive) play()
    }

    override fun upSpeechRate(reset: Boolean) {
        if (reset && !pause && contentList.isNotEmpty()) {
            playStop()
            play()
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<OfflineReadAloudService>(actionStr)
    }

    private fun isCurrentSession(sessionId: Long): Boolean =
        sessionId == playbackSessionId.get()

    private fun ensureSession(sessionId: Long) {
        if (!isCurrentSession(sessionId)) throw CancellationException("offline playback session changed")
    }
}
