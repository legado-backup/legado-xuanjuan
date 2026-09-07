package io.legado.app.help.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KokoroVoiceGalleryContractTest {

    @Test
    fun `voice gallery exposes role tabs recommended presets preview and advanced tuning`() {
        val layout = source("src/main/res/layout/dialog_kokoro_voice.xml")
        listOf(
            "tv_role_narrator",
            "tv_role_male",
            "tv_role_female",
            "tv_role_unknown",
            "tv_preset_1",
            "tv_preset_2",
            "tv_preset_3",
            "tv_preset_4",
            "tv_preview",
            "tv_advanced_toggle",
            "ll_advanced_detail",
            "seek_advanced",
            "tv_cancel",
            "tv_save",
        ).forEach { id -> assertTrue(layout.contains("@+id/$id")) }
        assertTrue(layout.contains("@drawable/xuanjuan_read_aloud_bg"))
        assertTrue(layout.contains("@drawable/xuanjuan_read_aloud_card"))
        assertTrue(layout.contains("@drawable/xuanjuan_read_aloud_action_bg"))
        assertTrue(layout.contains("@color/xuanjuan_read_aloud_text_primary"))
    }

    @Test
    fun `recommended speakers stay inside role specific Chinese ranges`() {
        val helper = source("src/main/java/io/legado/app/help/tts/KokoroOfflineTts.kt")
        assertTrue(helper.contains("intArrayOf(58, 60, 3, 12)"))
        assertTrue(helper.contains("intArrayOf(58, 60, 66, 72)"))
        assertTrue(helper.contains("intArrayOf(3, 5, 12, 21)"))
        assertTrue(helper.contains("intArrayOf(5, 60, 3, 66)"))
        assertTrue(helper.contains("fun speakerRange(role: SpeechRole): IntRange"))
        assertTrue(helper.contains("fun clampSpeaker(role: SpeechRole, value: Int): Int"))
    }

    @Test
    fun `preview is fully local serialized with live generation and does not persist selection`() {
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/KokoroVoiceDialog.kt")
        val helper = source("src/main/java/io/legado/app/help/tts/KokoroOfflineTts.kt")
        val service = source("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")

        assertTrue(dialog.contains("KokoroOfflineTts.acquire(appContext)"))
        assertTrue(dialog.contains("KokoroOfflineTts.withGeneration"))
        assertTrue(dialog.contains("AudioTrack.MODE_STATIC"))
        assertTrue(dialog.contains("R.string.kokoro_preview_text_narrator"))
        assertTrue(dialog.contains("R.string.kokoro_preview_text_male"))
        assertTrue(dialog.contains("R.string.kokoro_preview_text_female"))
        assertTrue(dialog.contains("R.string.kokoro_preview_text_unknown"))
        assertTrue(dialog.contains("presetView.text = label"))
        assertTrue(dialog.contains("renderAdvanced(false)"))
        assertFalse(
            dialog.contains(
                "presetView.text = getString(R.string.kokoro_voice_preset_format, label, preset.speaker)"
            )
        )
        assertTrue(helper.contains("private val generationLock = Any()"))
        assertTrue(service.contains("KokoroOfflineTts.withGeneration"))

        val preview = dialog.substringAfter("private fun previewCurrentVoice()")
            .substringBefore("private fun stopPreview()")
        assertFalse(preview.contains("AppConfig.kokoroNarratorSpeaker ="))
        assertFalse(preview.contains("AppConfig.kokoroMaleSpeaker ="))
        assertFalse(preview.contains("AppConfig.kokoroFemaleSpeaker ="))
        assertFalse(preview.contains("AppConfig.kokoroUnknownSpeaker ="))
    }

    @Test
    fun `speaker choices are temporary until explicit save`() {
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/KokoroVoiceDialog.kt")
        assertTrue(dialog.contains("private val pendingSpeakers = mutableMapOf<SpeechRole, Int>()"))
        assertTrue(dialog.contains("tvSave.setOnClickListener"))
        assertTrue(dialog.contains("saveSelections()"))
        assertTrue(dialog.contains("tvCancel.setOnClickListener { dismissAllowingStateLoss() }"))
        val slider = dialog.substringAfter("private fun bindAdvancedSlider()")
            .substringBefore("private fun selectRole")
        assertTrue(slider.contains("setPendingSpeaker"))
        assertFalse(slider.contains("AppConfig.kokoro"))
    }

    @Test
    fun `engine picker can open the gallery and immediately start a local preview`() {
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/KokoroVoiceDialog.kt")
        val picker = source("src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt")
        assertTrue(dialog.contains("ARG_AUTO_PREVIEW"))
        assertTrue(dialog.contains("previewCurrentVoice()"))
        assertTrue(picker.contains("KokoroVoiceDialog.newInstance(autoPreview = true)"))
    }

    private fun source(path: String): String {
        val file = sequenceOf(File(path), File("app/$path"), File("legado/app/$path"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $path" }
        return file.readText()
    }
}
