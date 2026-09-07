package io.legado.app.help.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FastVitsOfflineTtsContractTest {
    @Test fun `fast vits uses app private benchmarked model`() {
        val helper = source("src/main/java/io/legado/app/help/tts/FastVitsOfflineTts.kt")
        assertTrue(helper.contains("xuanjuan:vits-fast-offline:v1"))
        assertTrue(helper.contains("sherpa-onnx-vits-zh-ll"))
        assertTrue(helper.contains("File(context.filesDir, \"tts_models\")"))
        assertTrue(helper.contains("OfflineTtsVitsModelConfig("))
        assertTrue(helper.contains("numThreads = 2"))
        assertTrue(helper.contains("provider = \"cpu\""))
        assertFalse(helper.contains("context.assets"))
    }

    @Test fun `four roles use distinct valid speakers`() {
        val speakers = SpeechRole.entries.map(FastVitsOfflineTts::speakerFor)
        assertTrue(speakers.distinct().size == 4)
        assertTrue(speakers.all { it in 0 until FastVitsOfflineTts.NUM_SPEAKERS })
        assertTrue(FastVitsOfflineTts.speakerFor(SpeechRole.MALE) == 4)
    }

    @Test fun `read aloud routes fast vits through shared offline service`() {
        val readAloud = source("src/main/java/io/legado/app/model/ReadAloud.kt")
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
        assertTrue(readAloud.contains("FastVitsOfflineTts.ENGINE_TOKEN"))
        assertTrue(readAloud.contains("OfflineReadAloudService::class.java"))
        assertTrue(readAloud.contains("FastVitsOfflineTts.prewarm(context)"))
        assertTrue(service.contains("OfflineBackend.FAST_VITS"))
        assertTrue(service.contains("FastVitsOfflineTts.isInstalled(this)"))
        assertTrue(service.contains("FastVitsOfflineTts.speakerFor(chunk.role)"))
        assertTrue(service.contains("FastVitsOfflineTts.acquire(this)"))
        assertTrue(service.contains("FastVitsOfflineTts.withGeneration(block)"))
        assertTrue(service.contains("FastVitsOfflineTts.release(tts)"))
        assertTrue(service.contains("FastVitsOfflineTts.PLAYBACK_STATUS_EVENT"))
        assertTrue(service.contains("prepareBackendForSession(requestedBackend)"))
        assertTrue(service.contains("releaseOfflineTtsLocked()"))
    }

    @Test fun `fast vits model installer is app managed and atomically activated`() {
        val installer = source("src/main/java/io/legado/app/service/FastVitsModelInstallService.kt")
        val manifest = source("src/main/AndroidManifest.xml")
        val notificationIds = source("src/main/java/io/legado/app/constant/NotificationId.kt")
        assertTrue(installer.contains("FastVitsOfflineTts.MODEL_DOWNLOAD_URL"))
        assertTrue(installer.contains("FastVitsOfflineTts.requiredFiles") || installer.contains("missingModelFiles"))
        assertTrue(installer.contains("LibArchiveUtils.unArchive"))
        assertTrue(installer.contains(".install-"))
        assertTrue(installer.contains(".backup-"))
        assertTrue(installer.contains("FastVitsOfflineTts.ModelInstallPhase.DOWNLOADING"))
        assertTrue(installer.contains("downloadedBytes = copied"))
        assertTrue(installer.contains("FastVitsOfflineTts.MODEL_EVENT"))
        assertTrue(manifest.contains(".service.FastVitsModelInstallService"))
        assertTrue(notificationIds.contains("FastVitsModelInstallService = 112"))
    }

    @Test fun `engine picker exposes fast vits installation management and selection`() {
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt")
        assertTrue(dialog.contains("FastVitsOfflineTts.ENGINE_TOKEN"))
        assertTrue(dialog.contains("FastVitsModelInstallService.start"))
        assertTrue(dialog.contains("FastVitsModelInstallService.stop"))
        assertTrue(dialog.contains("updateFastVitsHeader"))
        assertTrue(dialog.contains("FastVitsOfflineTts.modelSizeBytes"))
        assertTrue(dialog.contains("FastVitsOfflineTts.deleteModel"))
        assertTrue(dialog.contains("FastVitsOfflineTts.prewarm(requireContext())"))
        assertTrue(dialog.contains("R.string.fast_vits_model_downloading_detail"))
        assertTrue(dialog.contains("tvPreview.gone()"))
    }

    @Test fun `read aloud console shows fast vits local speed and startup states`() {
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
        assertTrue(dialog.contains("isOfflineNeuralEngine()"))
        assertTrue(dialog.contains("FastVitsOfflineTts.prewarm(requireContext())"))
        assertTrue(dialog.contains("FastVitsOfflineTts.PLAYBACK_STATUS_EVENT"))
        assertTrue(dialog.contains("R.string.fast_vits_playback_loading_model"))
        assertTrue(dialog.contains("R.string.fast_vits_playback_first_audio"))
        assertTrue(dialog.contains("AppConfig.kokoroSpeechRate"))
        assertTrue(service.contains("R.string.fast_vits_playback_preparing"))
    }

    private fun source(path: String): String {
        val file = sequenceOf(File(path), File("app/$path")).firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $path" }
        return file.readText()
    }
}
