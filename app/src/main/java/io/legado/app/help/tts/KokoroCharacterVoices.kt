package io.legado.app.help.tts

import io.legado.app.data.entities.Book

/** Book-local character voice overrides. Exact character mappings always win over role defaults. */
internal object KokoroCharacterVoices {

    fun normalizeName(name: String?): String? {
        val normalized = name
            ?.trim()
            ?.trim('“', '”', '「', '」', '『', '』', '"', '\'', '，', ',', '。', '！', '!', '？', '?', '：', ':')
            ?.replace(Regex("\\s+"), "")
            .orEmpty()
        return normalized.takeIf { it.length in 2..6 && it.all(::isHan) }
    }

    fun sanitize(mappings: Map<String, Int>): Map<String, Int> = buildMap {
        mappings.forEach { (rawName, rawSpeaker) ->
            val name = normalizeName(rawName) ?: return@forEach
            put(name, rawSpeaker.coerceIn(KokoroOfflineTts.SPEAKER_MIN, KokoroOfflineTts.SPEAKER_MAX))
        }
    }

    fun mappings(book: Book?): Map<String, Int> =
        book?.config?.kokoroCharacterVoices?.let(::sanitize).orEmpty()

    fun speakerOverride(book: Book?, characterName: String?): Int? {
        val name = normalizeName(characterName) ?: return null
        return mappings(book)[name]
    }

    fun speakerFor(book: Book?, characterName: String?, fallbackRole: SpeechRole): Int =
        speakerOverride(book, characterName) ?: KokoroOfflineTts.speakerFor(fallbackRole)

    private fun isHan(char: Char): Boolean = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
}
