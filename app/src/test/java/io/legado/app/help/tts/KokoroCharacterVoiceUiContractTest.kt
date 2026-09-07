package io.legado.app.help.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KokoroCharacterVoiceUiContractTest {

    @Test
    fun `global voice gallery links to per book character voices`() {
        val layout = source("src/main/res/layout/dialog_kokoro_voice.xml")
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/KokoroVoiceDialog.kt")
        assertTrue(layout.contains("@+id/tv_character_voices"))
        assertTrue(dialog.contains("KokoroCharacterVoiceDialog()"))
        assertTrue(dialog.contains("ReadBook.book"))
    }

    @Test
    fun `character dialog exposes discovery manual correction preview reset and explicit save`() {
        val layout = source("src/main/res/layout/dialog_kokoro_character_voice.xml")
        listOf(
            "character_tabs_detected",
            "character_tabs_saved",
            "tv_saved_empty",
            "et_character_name",
            "tv_character_add",
            "tv_selected_character",
            "tv_character_preset_1",
            "tv_character_preset_2",
            "tv_character_preset_3",
            "tv_character_preset_4",
            "tv_character_preview",
            "tv_character_preview_quote",
            "tv_character_advanced_toggle",
            "ll_character_advanced_detail",
            "seek_character_advanced",
            "tv_character_reset",
            "tv_character_cancel",
            "tv_character_save",
        ).forEach { id -> assertTrue(layout.contains("@+id/$id")) }
        assertTrue(layout.contains("@drawable/xuanjuan_read_aloud_bg"))
        assertTrue(layout.contains("@drawable/xuanjuan_read_aloud_card"))
        assertTrue(layout.contains("@color/xuanjuan_read_aloud_text_primary"))
    }

    @Test
    fun `character choices stay temporary until save and preview is local`() {
        val dialog = source("src/main/java/io/legado/app/ui/book/read/config/KokoroCharacterVoiceDialog.kt")
        assertTrue(dialog.contains("private val pendingMappings = linkedMapOf<String, Int>()"))
        assertTrue(dialog.contains("KokoroOfflineTts.acquire(appContext)"))
        assertTrue(dialog.contains("KokoroOfflineTts.withGeneration"))
        assertTrue(dialog.contains("AudioTrack.MODE_STATIC"))
        assertTrue(dialog.contains("state.previewText ?: getString(R.string.kokoro_character_preview_text)"))
        assertTrue(dialog.contains("presetLabelResources(state.role)"))
        assertFalse(dialog.contains("view.text = getString(R.string.kokoro_character_preset, preset)"))
        assertTrue(dialog.contains("tvCharacterSave.setOnClickListener"))
        val beforeSave = dialog.substringBefore("private fun saveMappings()")
        assertFalse(beforeSave.contains("book.config.kokoroCharacterVoices ="))
        val save = dialog.substringAfter("private fun saveMappings()")
        assertTrue(save.contains("book.config.kokoroCharacterVoices ="))
        assertTrue(save.contains("book.update()"))
    }

    private fun source(path: String): String {
        val file = sequenceOf(File(path), File("app/$path"), File("legado/app/$path"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $path" }
        return file.readText()
    }
}
