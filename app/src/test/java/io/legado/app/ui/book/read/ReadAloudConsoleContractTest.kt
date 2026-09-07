package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudConsoleContractTest {

    @Test
    fun `console uses one clear sleep timer entry`() {
        val layout = projectFile("src/main/res/layout/dialog_read_aloud.xml")
        val dialog = projectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")

        assertTrue(layout.contains("@+id/ll_timer"))
        assertTrue(layout.contains("@string/sleep_timer_title"))
        assertFalse(layout.contains("@+id/seek_timer"))
        assertFalse(dialog.contains("seekTimer"))
        assertTrue(dialog.contains("llTimer.setOnClickListener"))
        assertTrue(dialog.contains("SleepTimerDialog.newInstance("))
        assertTrue(dialog.contains("R.string.sleep_timer_status_time"))
        assertTrue(dialog.contains("R.string.read_aloud_timer_status_chapter"))
    }

    @Test
    fun `console provides five common speeds and keeps fine adjustment`() {
        val layout = projectFile("src/main/res/layout/dialog_read_aloud.xml")
        val dialog = projectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")

        listOf("08", "10", "12", "15", "20").forEach {
            assertTrue(layout.contains("@+id/tv_speed_$it"))
        }
        assertTrue(layout.contains("@+id/seek_tts_speechRate"))
        assertTrue(dialog.contains("tvSpeed08.setOnClickListener { setSpeechRate(3) }"))
        assertTrue(dialog.contains("tvSpeed10.setOnClickListener { setSpeechRate(5) }"))
        assertTrue(dialog.contains("tvSpeed12.setOnClickListener { setSpeechRate(7) }"))
        assertTrue(dialog.contains("tvSpeed15.setOnClickListener { setSpeechRate(10) }"))
        assertTrue(dialog.contains("tvSpeed20.setOnClickListener { setSpeechRate(15) }"))
        assertTrue(dialog.contains("R.string.tts_speed_value"))
    }

    @Test
    fun `speech rate is clamped at persistence and console boundaries`() {
        val config = projectFile("src/main/java/io/legado/app/help/config/AppConfig.kt")
        val dialog = projectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")

        assertTrue(config.contains("const val minTtsSpeechRate = 0"))
        assertTrue(config.contains("const val maxTtsSpeechRate = 45"))
        assertTrue(config.contains("const val defaultKokoroSpeechRate = 10"))
        assertTrue(config.contains("var kokoroSpeechRate: Int"))
        assertTrue(config.split("coerceIn(minTtsSpeechRate, maxTtsSpeechRate)").size - 1 >= 2)
        assertTrue(dialog.contains("value.coerceIn(AppConfig.minTtsSpeechRate, AppConfig.maxTtsSpeechRate)"))
        assertFalse(dialog.contains("AppConfig.ttsSpeechRate += 1"))
        assertFalse(dialog.contains("AppConfig.ttsSpeechRate -= 1"))
    }

    @Test
    fun `local neural engines keep a dedicated recommended speed without changing system tts rate`() {
        val dialog = projectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        val service = projectFile("src/main/java/io/legado/app/service/OfflineReadAloudService.kt")
        val keys = projectFile("src/main/java/io/legado/app/constant/PreferKey.kt")

        assertTrue(keys.contains("const val kokoroSpeechRate = \"kokoroSpeechRate\""))
        assertTrue(dialog.contains("if (isOfflineNeuralEngine()) AppConfig.kokoroSpeechRate else AppConfig.ttsSpeechRate"))
        assertTrue(dialog.contains("FastVitsOfflineTts.ENGINE_TOKEN"))
        assertTrue(dialog.contains("AppConfig.kokoroSpeechRate = safeValue"))
        assertTrue(dialog.contains("cbTtsFollowSys.visible(!offlineNeural)"))
        assertTrue(dialog.contains("R.string.tts_speed_15x_recommended"))
        assertTrue(service.contains("AppConfig.kokoroSpeechRate"))
        val speechSpeed = service.substringAfter("private fun speechSpeed(): Float")
            .substringBefore("private suspend fun playSamples")
        assertFalse(speechSpeed.contains("AppConfig.ttsFlowSys"))
        assertFalse(speechSpeed.contains("AppConfig.ttsSpeechRate"))
    }

    @Test
    fun `console keeps core playback actions and adds speech position recovery`() {
        val layout = projectFile("src/main/res/layout/dialog_read_aloud.xml")
        val dialog = projectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")

        listOf(
            "@+id/tv_pre",
            "@+id/tv_next",
            "@+id/iv_play_prev",
            "@+id/iv_play_pause",
            "@+id/iv_stop",
            "@+id/iv_play_next",
            "@+id/ll_catalog",
            "@+id/ll_to_backstage",
            "@+id/ll_setting",
            "@+id/ll_engine",
            "@+id/ll_back_to_speech",
        ).forEach { assertTrue(layout.contains(it)) }
        assertTrue(dialog.contains("callBack?.backToSpeakingPosition()"))
        assertTrue(dialog.contains("EventBus.READ_ALOUD_FOLLOW"))
    }

    @Test
    fun `advanced read aloud settings are grouped without losing keys`() {
        val xml = projectFile("src/main/res/xml/pref_config_aloud.xml")

        assertEquals(
            3,
            Regex("<io\\.legado\\.app\\.lib\\.prefs\\.PreferenceCategory").findAll(xml).count()
        )
        assertTrue(xml.contains("@string/read_aloud_settings_playback"))
        assertTrue(xml.contains("@string/read_aloud_settings_follow"))
        assertTrue(xml.contains("@string/read_aloud_settings_engine"))
        listOf(
            "ignoreAudioFocus",
            "pauseReadAloudWhilePhoneCalls",
            "readAloudWakeLock",
            "mediaButtonPerNext",
            "readAloudByPage",
            "readAloudFollowManualPage",
            "streamReadAloudAudio",
            "appTtsEngine",
            "sysTtsConfig",
        ).forEach { key -> assertTrue(xml.contains("android:key=\"$key\"")) }
    }

    @Test
    fun `console uses dedicated brighter high contrast palette`() {
        val layout = projectFile("src/main/res/layout/dialog_read_aloud.xml")
        val dialog = projectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        val colors = projectFile("src/main/res/values/novel_helper_colors.xml")
        val nightColors = projectFile("src/main/res/values-night/novel_helper_colors.xml")
        val background = projectFile("src/main/res/drawable/xuanjuan_read_aloud_bg.xml")
        val card = projectFile("src/main/res/drawable/xuanjuan_read_aloud_card.xml")
        val action = projectFile("src/main/res/drawable/xuanjuan_read_aloud_action_bg.xml")

        assertTrue(layout.contains("@drawable/xuanjuan_read_aloud_bg"))
        assertTrue(layout.contains("@drawable/xuanjuan_read_aloud_card"))
        assertTrue(layout.contains("@drawable/xuanjuan_read_aloud_action_bg"))
        assertTrue(layout.contains("@color/xuanjuan_read_aloud_text_primary"))
        assertTrue(layout.contains("@color/xuanjuan_read_aloud_text_secondary"))
        assertTrue(layout.contains("@color/xuanjuan_read_aloud_action_icon"))
        assertFalse(layout.contains("@drawable/novel_helper_preference_card"))
        assertFalse(layout.contains("@drawable/xuanjuan_reader_action_bg"))
        assertTrue(dialog.contains("R.drawable.xuanjuan_read_aloud_bg"))
        assertTrue(colors.contains("<color name=\"xuanjuan_read_aloud_surface\">#2D231C</color>"))
        assertTrue(colors.contains("<color name=\"xuanjuan_read_aloud_text_primary\">#FFF8EA</color>"))
        assertTrue(nightColors.contains("<color name=\"xuanjuan_read_aloud_surface\">#251D18</color>"))
        assertTrue(background.contains("@color/xuanjuan_read_aloud_surface"))
        assertTrue(card.contains("@color/xuanjuan_read_aloud_card_high"))
        assertTrue(action.contains("@color/xuanjuan_read_aloud_gold"))
    }

    @Test
    fun `advanced read aloud settings also use dedicated high contrast cards`() {
        val configDialog = projectFile(
            "src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt"
        )
        val preference = projectFile("src/main/res/layout/xuanjuan_read_aloud_preference.xml")
        val category = projectFile(
            "src/main/res/layout/xuanjuan_read_aloud_preference_category.xml"
        )

        assertTrue(configDialog.contains("R.drawable.xuanjuan_read_aloud_bg"))
        assertTrue(configDialog.contains("applyReadAloudPreferenceCards(preferenceScreen)"))
        assertTrue(configDialog.contains("R.layout.xuanjuan_read_aloud_preference_category"))
        assertTrue(configDialog.contains("R.layout.xuanjuan_read_aloud_preference"))
        assertTrue(preference.contains("@drawable/xuanjuan_read_aloud_card"))
        assertTrue(preference.contains("@color/xuanjuan_read_aloud_text_primary"))
        assertTrue(preference.contains("@color/xuanjuan_read_aloud_text_secondary"))
        assertTrue(category.contains("@color/xuanjuan_read_aloud_gold"))
    }

    private fun projectFile(pathInApp: String): String {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
            .readText()
    }
}
