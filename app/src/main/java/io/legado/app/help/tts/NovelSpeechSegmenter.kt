package io.legado.app.help.tts

internal enum class SpeechRole {
    NARRATOR,
    MALE,
    FEMALE,
    UNKNOWN_DIALOGUE,
}

internal data class SpeechSegment(
    val text: String,
    val role: SpeechRole,
    val start: Int,
    val characterName: String? = null,
)

internal data class DiscoveredCharacter(
    val name: String,
    val role: SpeechRole,
    val occurrences: Int,
    val previewText: String? = null,
)

/**
 * Lightweight novel dialogue segmentation for offline TTS.
 *
 * We intentionally do not infer gender from a name. A dialogue becomes male/female only
 * when a nearby speech attribution contains an explicit gender cue; otherwise it uses the
 * unknown-dialogue voice. This keeps automatic multi-role reading predictable.
 */
internal object NovelSpeechSegmenter {

    private val quotePairs = mapOf(
        '“' to '”',
        '「' to '」',
        '『' to '』',
        '"' to '"',
    )

    private val speechVerb = Regex(
        "(?:说道|问道|答道|笑道|喊道|叫道|喝道|吼道|低声道|高声道|轻声道|沉声道|开口|回答|嘀咕|说|问|喊|叫|答)"
    )
    private val femaleCue = Regex(
        "(?:她|女孩|女生|女人|女子|姑娘|小姐|少女|母亲|妈妈|姐姐|妹妹|妻子|夫人|奶奶|婆婆|女儿|师姐|师妹)"
    )
    private val maleCue = Regex(
        "(?:他|男孩|男生|男人|男子|少年|青年|父亲|爸爸|哥哥|弟弟|丈夫|老者|爷爷|儿子|师兄|师弟)"
    )
    private val preferredBreak = Regex("[。！？!?；;，,、：:]$")

    private data class Attribution(
        val role: SpeechRole,
        val characterName: String?,
    )

    private val beforeName = Regex(
        "([\\p{IsHan}]{2,6})(?:(?:微微|忽然|轻轻|冷冷|淡淡|笑着|皱眉|抬头|点头|摇头))*" +
            speechVerb.pattern + "\\s*[：:,，。！？!?；;]*$"
    )
    private val afterName = Regex(
        "^[\\s，,。！？!?；;：:—-]*([\\p{IsHan}]{2,6})(?:(?:微微|忽然|轻轻|冷冷|淡淡|笑着|皱眉|抬头|点头|摇头))*" +
            speechVerb.pattern
    )
    private val removableNamePrefixes = listOf(
        "紧接着", "片刻后", "这时候", "这时", "此时", "那时", "随后", "忽然", "只见", "于是", "接着", "然后", "闻言",
        "少女", "少年", "女子", "男子", "姑娘", "公子", "老人", "老者", "青年", "女孩", "男孩", "女生", "男生", "女人", "男人", "小姐", "先生",
    )
    private val nonCharacterNames = setOf(
        "他们", "她们", "它们", "众人", "大家", "有人", "无人", "对方", "二人", "两人", "三人",
        "少女", "少年", "女子", "男子", "姑娘", "公子", "老人", "老者", "青年", "女孩", "男孩", "女生", "男生", "女人", "男人", "小姐", "先生",
        "父亲", "母亲", "爸爸", "妈妈", "哥哥", "姐姐", "弟弟", "妹妹", "丈夫", "妻子", "爷爷", "奶奶", "儿子", "女儿", "师兄", "师弟", "师姐", "师妹",
    )

    fun segment(text: String): List<SpeechSegment> {
        if (text.isEmpty()) return emptyList()
        val result = arrayListOf<SpeechSegment>()
        var plainStart = 0
        var index = 0
        while (index < text.length) {
            val open = text[index]
            val close = quotePairs[open]
            if (close == null) {
                index++
                continue
            }
            val end = findQuoteEnd(text, index + 1, open, close)
            if (end < 0) {
                index++
                continue
            }
            if (plainStart < index) {
                addMerged(result, text.substring(plainStart, index), SpeechRole.NARRATOR, plainStart)
            }
            val before = text.substring(maxOf(0, index - ATTRIBUTION_WINDOW), index)
            val afterEnd = minOf(text.length, end + 1 + ATTRIBUTION_WINDOW)
            val after = text.substring(end + 1, afterEnd)
            val attribution = classifyDialogue(before, after)
            addMerged(
                result,
                text.substring(index, end + 1),
                attribution.role,
                index,
                attribution.characterName,
            )
            index = end + 1
            plainStart = index
        }
        if (plainStart < text.length) {
            addMerged(result, text.substring(plainStart), SpeechRole.NARRATOR, plainStart)
        }
        return result.ifEmpty { listOf(SpeechSegment(text, SpeechRole.NARRATOR, 0)) }
    }

    fun chunk(text: String, maxChars: Int = 160): List<SpeechSegment> {
        require(maxChars >= 32)
        val chunks = arrayListOf<SpeechSegment>()
        segment(text).forEach { segment ->
            var offset = 0
            while (offset < segment.text.length) {
                val remaining = segment.text.length - offset
                val length = if (remaining <= maxChars) {
                    remaining
                } else {
                    findChunkLength(segment.text, offset, maxChars)
                }
                val part = segment.text.substring(offset, offset + length)
                if (part.isNotBlank()) {
                    chunks += SpeechSegment(
                        text = part,
                        role = segment.role,
                        start = segment.start + offset,
                        characterName = segment.characterName,
                    )
                }
                offset += length
            }
        }
        return chunks
    }

    private fun findQuoteEnd(text: String, start: Int, open: Char, close: Char): Int {
        if (open == close) return text.indexOf(close, start)
        var nested = 0
        for (index in start until text.length) {
            when (text[index]) {
                open -> nested++
                close -> if (nested == 0) return index else nested--
            }
        }
        return -1
    }

    fun discoverCharacters(text: String): List<DiscoveredCharacter> {
        data class Evidence(
            var male: Int = 0,
            var female: Int = 0,
            var unknown: Int = 0,
            var previewText: String? = null,
        )
        val evidence = linkedMapOf<String, Evidence>()
        segment(text).forEach { segment ->
            val name = segment.characterName ?: return@forEach
            val item = evidence.getOrPut(name) { Evidence() }
            if (item.previewText == null) {
                item.previewText = segment.text
                    .trim()
                    .trim('“', '”', '「', '」', '『', '』', '"')
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.take(96)
            }
            when (segment.role) {
                SpeechRole.MALE -> item.male++
                SpeechRole.FEMALE -> item.female++
                else -> item.unknown++
            }
        }
        return evidence.map { (name, item) ->
            val role = when {
                item.male > 0 && item.female == 0 -> SpeechRole.MALE
                item.female > 0 && item.male == 0 -> SpeechRole.FEMALE
                else -> SpeechRole.UNKNOWN_DIALOGUE
            }
            DiscoveredCharacter(
                name = name,
                role = role,
                occurrences = item.male + item.female + item.unknown,
                previewText = item.previewText,
            )
        }.sortedWith(compareByDescending<DiscoveredCharacter> { it.occurrences }.thenBy { it.name })
    }

    private fun classifyDialogue(before: String, after: String): Attribution {
        var role = SpeechRole.UNKNOWN_DIALOGUE
        sequenceOf(before, after).forEach { attribution ->
            if (!speechVerb.containsMatchIn(attribution)) return@forEach
            val female = femaleCue.containsMatchIn(attribution)
            val male = maleCue.containsMatchIn(attribution)
            if (female xor male && role == SpeechRole.UNKNOWN_DIALOGUE) {
                role = if (female) SpeechRole.FEMALE else SpeechRole.MALE
            }
        }
        val characterName = extractCharacterName(before, beforeQuote = true)
            ?: extractCharacterName(after, beforeQuote = false)
        return Attribution(role, characterName)
    }

    private fun extractCharacterName(text: String, beforeQuote: Boolean): String? {
        if (!speechVerb.containsMatchIn(text)) return null
        val raw = if (beforeQuote) {
            beforeName.findAll(text).lastOrNull()?.groupValues?.getOrNull(1)
        } else {
            afterName.find(text)?.groupValues?.getOrNull(1)
        } ?: return null
        var candidate = raw
        var changed = true
        while (changed) {
            changed = false
            removableNamePrefixes.firstOrNull {
                candidate.startsWith(it) && candidate.length - it.length >= 2
            }?.let { prefix ->
                candidate = candidate.removePrefix(prefix)
                changed = true
            }
        }
        if (candidate.length !in 2..4 || candidate in nonCharacterNames) return null
        return candidate
    }

    private fun findChunkLength(text: String, offset: Int, maxChars: Int): Int {
        val minBreak = offset + maxChars / 2
        val hardEnd = minOf(text.length, offset + maxChars)
        for (end in hardEnd downTo minBreak) {
            if (preferredBreak.containsMatchIn(text.substring(end - 1, end))) {
                return end - offset
            }
        }
        return hardEnd - offset
    }

    private fun addMerged(
        result: MutableList<SpeechSegment>,
        text: String,
        role: SpeechRole,
        start: Int,
        characterName: String? = null,
    ) {
        if (text.isEmpty()) return
        val previous = result.lastOrNull()
        if (previous != null && previous.role == role && previous.characterName == characterName &&
            previous.start + previous.text.length == start
        ) {
            result[result.lastIndex] = previous.copy(text = previous.text + text)
        } else {
            result += SpeechSegment(text, role, start, characterName)
        }
    }

    private const val ATTRIBUTION_WINDOW = 28
}
