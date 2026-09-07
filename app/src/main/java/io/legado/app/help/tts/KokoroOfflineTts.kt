package io.legado.app.help.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object KokoroOfflineTts {

    enum class ModelInstallPhase {
        IDLE,
        PREPARING,
        DOWNLOADING,
        EXTRACTING,
        READY,
        FAILED,
        CANCELED,
    }

    data class ModelInstallSnapshot(
        val phase: ModelInstallPhase = ModelInstallPhase.IDLE,
        val progress: Int = -1,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    )

    const val ENGINE_TOKEN = "xuanjuan:kokoro-offline:v1"
    const val MODEL_EVENT = "xuanjuanKokoroModelChanged"
    const val PLAYBACK_STATUS_EVENT = "xuanjuanKokoroPlaybackStatus"
    const val PLAYBACK_STATUS_IDLE = 0
    const val PLAYBACK_STATUS_LOADING_MODEL = 1
    const val PLAYBACK_STATUS_FIRST_AUDIO = 2
    const val WARM_IDLE_RELEASE_MS = 120_000L
    const val MODEL_DIR_NAME = "kokoro-int8-multi-lang-v1_1"
    const val MODEL_DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"

    @Volatile
    private var installSnapshot = ModelInstallSnapshot()

    const val FEMALE_SPEAKER_MIN = 3
    const val FEMALE_SPEAKER_MAX = 57
    const val MALE_SPEAKER_MIN = 58
    const val MALE_SPEAKER_MAX = 102
    const val SPEAKER_MIN = FEMALE_SPEAKER_MIN
    const val SPEAKER_MAX = MALE_SPEAKER_MAX

    private val narratorPresetSpeakers = intArrayOf(58, 60, 3, 12)
    private val malePresetSpeakers = intArrayOf(58, 60, 66, 72)
    private val femalePresetSpeakers = intArrayOf(3, 5, 12, 21)
    private val unknownPresetSpeakers = intArrayOf(5, 60, 3, 66)

    private val warmLock = Any()
    private val generationLock = Any()
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmTts: OfflineTts? = null
    private var warmLeases = 0
    private var warmReleaseJob: Job? = null

    val requiredFiles = listOf(
        "model.int8.onnx",
        "voices.bin",
        "tokens.txt",
        "lexicon-us-en.txt",
        "lexicon-zh.txt",
        "phone-zh.fst",
        "date-zh.fst",
        "number-zh.fst",
    )

    fun modelRoot(context: Context): File = File(context.filesDir, "tts_models")

    fun modelDir(context: Context): File = File(modelRoot(context), MODEL_DIR_NAME)

    fun missingModelFiles(context: Context): List<String> = missingModelFiles(modelDir(context))

    fun missingModelFiles(dir: File): List<String> {
        val missing = requiredFiles.filter { path ->
            val file = File(dir, path)
            !file.isFile || file.length() <= 0L
        }.toMutableList()
        val espeakData = File(dir, "espeak-ng-data")
        if (!espeakData.isDirectory || espeakData.listFiles().isNullOrEmpty()) {
            missing += "espeak-ng-data/"
        }
        return missing
    }

    fun isInstalled(context: Context): Boolean = missingModelFiles(context).isEmpty()

    fun modelInstallSnapshot(): ModelInstallSnapshot = installSnapshot

    fun updateModelInstallSnapshot(snapshot: ModelInstallSnapshot) {
        installSnapshot = snapshot
    }

    fun modelSizeBytes(context: Context): Long {
        val dir = modelDir(context)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length().coerceAtLeast(0L) }
    }

    fun deleteModel(context: Context): Boolean = synchronized(warmLock) {
        if (warmLeases > 0) return@synchronized false
        warmReleaseJob?.cancel()
        warmReleaseJob = null
        warmTts?.runCatching { release() }
        warmTts = null
        val dir = modelDir(context)
        val deleted = !dir.exists() || dir.deleteRecursively()
        if (deleted) {
            installSnapshot = ModelInstallSnapshot(ModelInstallPhase.IDLE)
        }
        deleted
    }

    fun prewarm(context: Context) {
        if (!isInstalled(context)) return
        val appContext = context.applicationContext
        warmScope.launch {
            runCatching {
                synchronized(warmLock) {
                    if (warmTts == null) {
                        warmTts = create(appContext)
                    }
                    scheduleWarmReleaseLocked()
                }
            }.onFailure {
                AppLog.putDebug("Kokoro 预热失败: ${it.localizedMessage}")
            }
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

    fun speakerFor(role: SpeechRole): Int = when (role) {
        SpeechRole.NARRATOR -> AppConfig.kokoroNarratorSpeaker
        SpeechRole.MALE -> AppConfig.kokoroMaleSpeaker
        SpeechRole.FEMALE -> AppConfig.kokoroFemaleSpeaker
        SpeechRole.UNKNOWN_DIALOGUE -> AppConfig.kokoroUnknownSpeaker
    }

    fun presetSpeakers(role: SpeechRole): IntArray = when (role) {
        SpeechRole.NARRATOR -> narratorPresetSpeakers
        SpeechRole.MALE -> malePresetSpeakers
        SpeechRole.FEMALE -> femalePresetSpeakers
        SpeechRole.UNKNOWN_DIALOGUE -> unknownPresetSpeakers
    }.copyOf()

    fun speakerRange(role: SpeechRole): IntRange = when (role) {
        SpeechRole.MALE -> MALE_SPEAKER_MIN..MALE_SPEAKER_MAX
        SpeechRole.FEMALE -> FEMALE_SPEAKER_MIN..FEMALE_SPEAKER_MAX
        SpeechRole.NARRATOR,
        SpeechRole.UNKNOWN_DIALOGUE -> SPEAKER_MIN..SPEAKER_MAX
    }

    fun clampSpeaker(role: SpeechRole, value: Int): Int {
        val range = speakerRange(role)
        return value.coerceIn(range.first, range.last)
    }

    fun <T> withGeneration(block: () -> T): T = synchronized(generationLock) {
        block()
    }

    fun clampNarratorSpeaker(value: Int): Int = value.coerceIn(SPEAKER_MIN, SPEAKER_MAX)
    fun clampUnknownSpeaker(value: Int): Int = value.coerceIn(SPEAKER_MIN, SPEAKER_MAX)
    fun clampMaleSpeaker(value: Int): Int = value.coerceIn(MALE_SPEAKER_MIN, MALE_SPEAKER_MAX)
    fun clampFemaleSpeaker(value: Int): Int = value.coerceIn(FEMALE_SPEAKER_MIN, FEMALE_SPEAKER_MAX)

    fun create(context: Context): OfflineTts {
        val dir = modelDir(context)
        val missing = missingModelFiles(dir)
        check(missing.isEmpty()) { "Kokoro 模型不完整: ${missing.joinToString()}" }
        val dictDir = File(dir, "dict").takeIf { it.isDirectory }?.absolutePath.orEmpty()
        val kokoro = OfflineTtsKokoroModelConfig(
            model = File(dir, "model.int8.onnx").absolutePath,
            voices = File(dir, "voices.bin").absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath,
            dataDir = File(dir, "espeak-ng-data").absolutePath,
            lexicon = listOf(
                File(dir, "lexicon-us-en.txt").absolutePath,
                File(dir, "lexicon-zh.txt").absolutePath,
            ).joinToString(","),
            dictDir = dictDir,
        )
        val model = OfflineTtsModelConfig(
            kokoro = kokoro,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
        return OfflineTts(
            null,
            OfflineTtsConfig(
                model = model,
                ruleFsts = listOf(
                    File(dir, "phone-zh.fst").absolutePath,
                    File(dir, "date-zh.fst").absolutePath,
                    File(dir, "number-zh.fst").absolutePath,
                ).joinToString(","),
                maxNumSentences = 1,
                silenceScale = 0.2f,
            ),
        )
    }
}
