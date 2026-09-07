package io.legado.app.help.tts

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.compress.LibArchiveUtils
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

class VitsOfflineTtsBenchmarkTest {

    @Test
    fun shortDialogueAtOnePointFiveXReportsRealTimeFactor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = ensureModel(context.filesDir, context.cacheDir)

        val loadStartedAt = SystemClock.elapsedRealtime()
        val tts = createTts(modelDir)
        val modelLoadMs = SystemClock.elapsedRealtime() - loadStartedAt
        try {
            val numSpeakers = tts.numSpeakers()
            val sampleRate = tts.sampleRate()
            Log.i(TAG, "modelLoadMs=$modelLoadMs sampleRate=$sampleRate numSpeakers=$numSpeakers")
            assertTrue("VITS benchmark model should expose many speakers", numSpeakers >= 100)

            // Separate one warm-up from the measured stress window so model allocation/JIT does not
            // hide the sustained synthesis throughput that determines whether a playback queue drains.
            val warmStartedAt = SystemClock.elapsedRealtime()
            val warm = tts.generate("开始测试。", 0, SPEED)
            val warmElapsedMs = SystemClock.elapsedRealtime() - warmStartedAt
            val warmAudioMs = audioDurationMs(warm.samples.size, warm.sampleRate)
            val warmRtf = rtf(warmElapsedMs, warmAudioMs)
            assertTrue("VITS warm-up should generate audio", warm.samples.isNotEmpty())
            Log.i(
                TAG,
                "warmup elapsedMs=$warmElapsedMs audioMs=$warmAudioMs rtf=$warmRtf"
            )

            var totalSynthMs = 0L
            var totalAudioMs = 0L
            var totalChars = 0L
            var maxRtf = 0f
            stressLines.forEachIndexed { index, text ->
                val sid = index % 5
                val startedAt = SystemClock.elapsedRealtime()
                val audio = tts.generate(text, sid, SPEED)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val audioMs = audioDurationMs(audio.samples.size, audio.sampleRate)
                val itemRtf = rtf(elapsedMs, audioMs)
                assertTrue("VITS generated empty audio for line $index", audio.samples.isNotEmpty())
                totalSynthMs += elapsedMs
                totalAudioMs += audioMs
                totalChars += text.length
                maxRtf = max(maxRtf, itemRtf)
                Log.i(
                    TAG,
                    "line=$index sid=$sid textLength=${text.length} elapsedMs=$elapsedMs " +
                        "audioMs=$audioMs rtf=$itemRtf"
                )
            }

            assertTrue("VITS benchmark should produce measurable audio", totalAudioMs > 0L)
            val aggregateRtf = rtf(totalSynthMs, totalAudioMs)
            val charsPerSecond = if (totalSynthMs > 0L) {
                totalChars * 1000f / totalSynthMs
            } else {
                0f
            }
            Log.i(
                TAG,
                "SUMMARY speed=$SPEED threads=2 lines=${stressLines.size} " +
                    "synthMs=$totalSynthMs audioMs=$totalAudioMs rtf=$aggregateRtf " +
                    "maxRtf=$maxRtf chars=$totalChars charsPerSecond=$charsPerSecond " +
                    "sampleRate=$sampleRate numSpeakers=$numSpeakers modelLoadMs=$modelLoadMs"
            )
        } finally {
            tts.release()
        }
    }

    private fun createTts(dir: File): OfflineTts {
        val vits = OfflineTtsVitsModelConfig(
            model = File(dir, "model.onnx").absolutePath,
            lexicon = File(dir, "lexicon.txt").absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath,
            dataDir = "",
            dictDir = File(dir, "dict").takeIf(File::isDirectory)?.absolutePath.orEmpty(),
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1f,
        )
        val model = OfflineTtsModelConfig(
            vits = vits,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
        val ruleFsts = listOf("phone.fst", "number.fst", "date.fst")
            .map { File(dir, it) }
            .filter(File::isFile)
            .joinToString(",") { it.absolutePath }
        return OfflineTts(
            null,
            OfflineTtsConfig(
                model = model,
                ruleFsts = ruleFsts,
                maxNumSentences = 1,
                silenceScale = 0.2f,
            ),
        )
    }

    private fun ensureModel(filesDir: File, cacheDir: File): File {
        val root = File(filesDir, "tts_models/vits-aishell3-benchmark")
        resolveModelDir(root)?.let { return it }

        root.deleteRecursively()
        root.mkdirs()
        val archive = File(cacheDir, "$MODEL_DIR_NAME.tar.bz2")
        downloadModel(archive)
        ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            LibArchiveUtils.unArchive(pfd, root)
        }
        archive.delete()
        return requireNotNull(resolveModelDir(root)) {
            "Extracted VITS model is incomplete under ${root.absolutePath}"
        }
    }

    private fun resolveModelDir(root: File): File? {
        if (!root.exists()) return null
        return root.walkTopDown()
            .maxDepth(2)
            .firstOrNull { dir ->
                dir.isDirectory &&
                    File(dir, "model.onnx").isFile &&
                    File(dir, "tokens.txt").isFile &&
                    File(dir, "lexicon.txt").isFile
            }
    }

    private fun downloadModel(target: File) {
        target.parentFile?.mkdirs()
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()
        var lastIoError: IOException? = null
        repeat(DOWNLOAD_ATTEMPTS) { attemptIndex ->
            var resumeFrom = target.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
            try {
                val request = Request.Builder()
                    .url(MODEL_DOWNLOAD_URL)
                    .apply {
                        if (resumeFrom > 0L) {
                            header("Range", "bytes=$resumeFrom-")
                        }
                    }
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) {
                        "VITS model download failed HTTP ${response.code}"
                    }
                    val append = resumeFrom > 0L && response.code == 206
                    if (!append) resumeFrom = 0L
                    val bodyBytes = response.body.contentLength().coerceAtLeast(0L)
                    val contentRangeTotal = response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                    val totalBytes = contentRangeTotal ?: (resumeFrom + bodyBytes)
                    var copied = resumeFrom
                    var lastLoggedMb = copied / (1024L * 1024L) - 1L
                    response.body.byteStream().buffered().use { input ->
                        FileOutputStream(target, append).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                val copiedMb = copied / (1024L * 1024L)
                                if (copiedMb != lastLoggedMb && copiedMb % 10L == 0L) {
                                    lastLoggedMb = copiedMb
                                    Log.i(TAG, "downloaded=${copiedMb}MB totalBytes=$totalBytes")
                                }
                            }
                        }
                    }
                    check(target.length() > 0L) { "Downloaded VITS archive is empty" }
                    if (totalBytes > 0L) {
                        check(target.length() == totalBytes) {
                            "Incomplete VITS archive ${target.length()}/$totalBytes"
                        }
                    }
                    Log.i(TAG, "downloadComplete bytes=${target.length()} totalBytes=$totalBytes")
                }
                return
            } catch (error: IOException) {
                lastIoError = error
                Log.i(
                    TAG,
                    "downloadRetry attempt=${attemptIndex + 1}/$DOWNLOAD_ATTEMPTS " +
                        "downloadedBytes=${target.length()} cause=${error.javaClass.simpleName}:" +
                        "${error.localizedMessage}"
                )
                if (attemptIndex + 1 < DOWNLOAD_ATTEMPTS) {
                    Thread.sleep(750L)
                }
            }
        }
        throw lastIoError ?: IOException("VITS model download failed")
    }

    private fun audioDurationMs(sampleCount: Int, sampleRate: Int): Long =
        if (sampleRate > 0) sampleCount * 1000L / sampleRate else 0L

    private fun rtf(elapsedMs: Long, audioMs: Long): Float =
        if (audioMs > 0L) elapsedMs.toFloat() / audioMs else 0f

    companion object {
        private const val TAG = "VitsTtsBench"
        private const val SPEED = 1.5f
        private const val DOWNLOAD_ATTEMPTS = 5
        private const val MODEL_DIR_NAME = "vits-icefall-zh-aishell3"
        private const val MODEL_DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                "vits-icefall-zh-aishell3.tar.bz2"

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
