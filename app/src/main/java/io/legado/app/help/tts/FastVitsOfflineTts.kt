package io.legado.app.help.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.legado.app.constant.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object FastVitsOfflineTts {
    enum class ModelInstallPhase { IDLE, PREPARING, DOWNLOADING, EXTRACTING, READY, FAILED, CANCELED }

    data class ModelInstallSnapshot(
        val phase: ModelInstallPhase = ModelInstallPhase.IDLE,
        val progress: Int = -1,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    )

    const val ENGINE_TOKEN = "xuanjuan:vits-fast-offline:v1"
    const val MODEL_EVENT = "xuanjuanFastVitsModelChanged"
    const val PLAYBACK_STATUS_EVENT = "xuanjuanFastVitsPlaybackStatus"
    const val PLAYBACK_STATUS_IDLE = 0
    const val PLAYBACK_STATUS_LOADING_MODEL = 1
    const val PLAYBACK_STATUS_FIRST_AUDIO = 2
    const val WARM_IDLE_RELEASE_MS = 120_000L
    const val MODEL_DIR_NAME = "sherpa-onnx-vits-zh-ll"
    const val MODEL_DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "sherpa-onnx-vits-zh-ll.tar.bz2"

    const val NARRATOR_SPEAKER = 0
    const val MALE_SPEAKER = 4
    const val FEMALE_SPEAKER = 1
    const val UNKNOWN_SPEAKER = 2
    const val NUM_SPEAKERS = 5

    @Volatile private var installSnapshot = ModelInstallSnapshot()
    private val warmLock = Any()
    private val generationLock = Any()
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmTts: OfflineTts? = null
    private var warmLeases = 0
    private var warmReleaseJob: Job? = null

    val requiredFiles = listOf("model.onnx", "tokens.txt", "lexicon.txt")

    fun modelRoot(context: Context): File = File(context.filesDir, "tts_models")
    fun modelDir(context: Context): File = File(modelRoot(context), MODEL_DIR_NAME)
    fun missingModelFiles(context: Context): List<String> = missingModelFiles(modelDir(context))

    fun missingModelFiles(dir: File): List<String> {
        val missing = requiredFiles.filter { name ->
            val file = File(dir, name)
            !file.isFile || file.length() <= 0L
        }.toMutableList()
        val dictDir = File(dir, "dict")
        if (!dictDir.isDirectory || dictDir.listFiles().isNullOrEmpty()) missing += "dict/"
        return missing
    }

    fun isInstalled(context: Context): Boolean = missingModelFiles(context).isEmpty()
    fun modelInstallSnapshot(): ModelInstallSnapshot = installSnapshot
    fun updateModelInstallSnapshot(snapshot: ModelInstallSnapshot) { installSnapshot = snapshot }

    fun modelSizeBytes(context: Context): Long {
        val dir = modelDir(context)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter(File::isFile).sumOf { it.length().coerceAtLeast(0L) }
    }

    fun deleteModel(context: Context): Boolean = synchronized(warmLock) {
        if (warmLeases > 0) return@synchronized false
        warmReleaseJob?.cancel()
        warmReleaseJob = null
        warmTts?.runCatching { release() }
        warmTts = null
        val dir = modelDir(context)
        val deleted = !dir.exists() || dir.deleteRecursively()
        if (deleted) installSnapshot = ModelInstallSnapshot(ModelInstallPhase.IDLE)
        deleted
    }

    fun speakerFor(role: SpeechRole): Int = when (role) {
        SpeechRole.NARRATOR -> NARRATOR_SPEAKER
        SpeechRole.MALE -> MALE_SPEAKER
        SpeechRole.FEMALE -> FEMALE_SPEAKER
        SpeechRole.UNKNOWN_DIALOGUE -> UNKNOWN_SPEAKER
    }

    fun prewarm(context: Context) {
        if (!isInstalled(context)) return
        val appContext = context.applicationContext
        warmScope.launch {
            runCatching {
                synchronized(warmLock) {
                    if (warmTts == null) warmTts = create(appContext)
                    scheduleWarmReleaseLocked()
                }
            }.onFailure { AppLog.putDebug("VITS 预热失败: ${it.localizedMessage}") }
        }
    }

    fun acquire(context: Context): OfflineTts = synchronized(warmLock) {
        warmReleaseJob?.cancel()
        warmReleaseJob = null
        val tts = warmTts ?: create(context.applicationContext).also { warmTts = it }
        warmLeases++
        tts
    }

    fun release(tts: OfflineTts) {
        synchronized(warmLock) {
            if (warmTts !== tts) {
                runCatching { tts.release() }
                return
            }
            warmLeases = (warmLeases - 1).coerceAtLeast(0)
            scheduleWarmReleaseLocked()
        }
    }

    private fun scheduleWarmReleaseLocked() {
        warmReleaseJob?.cancel()
        warmReleaseJob = null
        if (warmLeases > 0 || warmTts == null) return
        warmReleaseJob = warmScope.launch {
            delay(WARM_IDLE_RELEASE_MS)
            synchronized(warmLock) {
                if (warmLeases == 0) {
                    warmTts?.runCatching { release() }
                    warmTts = null
                    warmReleaseJob = null
                }
            }
        }
    }

    fun <T> withGeneration(block: () -> T): T = synchronized(generationLock) { block() }

    fun create(context: Context): OfflineTts {
        val dir = modelDir(context)
        val missing = missingModelFiles(dir)
        check(missing.isEmpty()) { "VITS 模型不完整: ${missing.joinToString()}" }
        val vits = OfflineTtsVitsModelConfig(
            model = File(dir, "model.onnx").absolutePath,
            lexicon = File(dir, "lexicon.txt").absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath,
            dataDir = "",
            dictDir = File(dir, "dict").absolutePath,
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
}
