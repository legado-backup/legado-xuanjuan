package io.legado.app.ui.book.read.config

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.OfflineTts
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogKokoroCharacterVoiceBinding
import io.legado.app.help.book.update
import io.legado.app.help.tts.KokoroCharacterVoices
import io.legado.app.help.tts.KokoroOfflineTts
import io.legado.app.help.tts.NovelSpeechSegmenter
import io.legado.app.help.tts.SpeechRole
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KokoroCharacterVoiceDialog : BaseDialogFragment(R.layout.dialog_kokoro_character_voice) {

    private data class CharacterState(
        val name: String,
        val role: SpeechRole,
        val occurrences: Int,
        val previewText: String? = null,
    )

    private val binding by viewBinding(DialogKokoroCharacterVoiceBinding::bind)
    private lateinit var book: Book
    private val characters = mutableListOf<CharacterState>()
    private val pendingMappings = linkedMapOf<String, Int>()
    private var selectedName: String? = null
    private var rendering = false
    private var previewJob: Job? = null
    private var previewToken = 0L
    private var resumeReadAloudAfterPreview = false

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.92f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val currentBook = ReadBook.book
        if (currentBook == null) {
            toastOnUi(R.string.kokoro_character_no_book)
            dismissAllowingStateLoss()
            return
        }
        book = currentBook
        pendingMappings.putAll(KokoroCharacterVoices.mappings(book))
        loadCharacters()
        bindActions()
        bindAdvancedSlider()
        KokoroOfflineTts.prewarm(requireContext())
        selectedName = characters.firstOrNull()?.name
        renderCharacterTabs()
        renderSelectedCharacter()
        renderAdvanced(false)
    }

    override fun onDestroyView() {
        stopPreview(resumeReadAloud = true)
        super.onDestroyView()
    }

    private fun loadCharacters() {
        val discovered = NovelSpeechSegmenter.discoverCharacters(ReadBook.currentChapterPlainText().orEmpty())
        discovered.forEach { item ->
            characters += CharacterState(
                item.name,
                item.role,
                item.occurrences,
                item.previewText,
            )
        }
        pendingMappings.keys.forEach { savedName ->
            if (characters.none { it.name == savedName }) {
                characters += CharacterState(savedName, SpeechRole.UNKNOWN_DIALOGUE, 0)
            }
        }
    }

    private fun bindActions() = binding.run {
        presetViews().forEachIndexed { index, textView ->
            textView.setOnClickListener {
                val state = selectedState() ?: return@setOnClickListener
                KokoroOfflineTts.presetSpeakers(state.role).getOrNull(index)?.let(::setSpeaker)
            }
        }
        tvCharacterPreview.setOnClickListener { previewCurrentVoice() }
        tvCharacterAdvancedToggle.setOnClickListener {
            renderAdvanced(!llCharacterAdvancedDetail.isVisible)
        }
        tvCharacterReset.setOnClickListener {
            val selected = selectedState()
            selectedName?.let { pendingMappings.remove(it) }
            if (selected != null && selected.occurrences <= 0) {
                characters.removeAll { it.name == selected.name }
                selectedName = characters.firstOrNull()?.name
            }
            stopPreview(resumeReadAloud = true)
            renderCharacterTabs()
            renderSelectedCharacter()
            renderAdvanced(false)
        }
        tvCharacterAdd.setOnClickListener { addManualCharacter() }
        tvCharacterCancel.setOnClickListener { dismissAllowingStateLoss() }
        tvCharacterSave.setOnClickListener {
            saveMappings()
            dismissAllowingStateLoss()
        }
    }

    private fun bindAdvancedSlider() {
        binding.seekCharacterAdvanced.max = KokoroOfflineTts.SPEAKER_MAX - KokoroOfflineTts.SPEAKER_MIN
        binding.seekCharacterAdvanced.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !rendering) {
                    setSpeaker(KokoroOfflineTts.SPEAKER_MIN + progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun addManualCharacter() {
        val name = KokoroCharacterVoices.normalizeName(binding.etCharacterName.text?.toString())
        if (name == null) {
            toastOnUi(R.string.kokoro_character_name_invalid)
            return
        }
        binding.etCharacterName.text?.clear()
        if (characters.none { it.name == name }) {
            characters += CharacterState(name, SpeechRole.UNKNOWN_DIALOGUE, 0)
        }
        pendingMappings.putIfAbsent(
            name,
            KokoroOfflineTts.speakerFor(SpeechRole.UNKNOWN_DIALOGUE),
        )
        selectedName = name
        renderCharacterTabs()
        renderSelectedCharacter()
        renderAdvanced(false)
    }

    private fun renderCharacterTabs(): Unit = binding.run {
        val detected = characters.filter { it.occurrences > 0 }
        val saved = characters.filter { pendingMappings.containsKey(it.name) }
        renderTabs(characterTabsDetected, detected)
        renderTabs(characterTabsSaved, saved)
        tvEmpty.isVisible = detected.isEmpty()
        tvSavedEmpty.isVisible = saved.isEmpty()
    }

    private fun renderTabs(container: ViewGroup, states: List<CharacterState>) {
        container.removeAllViews()
        states.forEach { state ->
            val tab = TextView(requireContext()).apply {
                text = state.name
                contentDescription = getString(R.string.kokoro_character_tab_accessibility, state.name)
                gravity = android.view.Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.xuanjuan_read_aloud_text_primary))
                setBackgroundResource(R.drawable.selector_read_aloud_speed)
                isSelected = state.name == selectedName
                setOnClickListener {
                    stopPreview(resumeReadAloud = true)
                    selectedName = state.name
                    renderCharacterTabs()
                    renderSelectedCharacter()
                    renderAdvanced(false)
                }
            }
            container.addView(
                tab,
                ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                ).apply { marginEnd = dp(6) },
            )
        }
    }

    private fun renderSelectedCharacter() = binding.run {
        val state = selectedState()
        characterEditor.isVisible = state != null
        if (state == null) return@run
        tvSelectedCharacter.text = state.name
        tvCharacterHint.text = when {
            state.occurrences <= 0 -> getString(R.string.kokoro_character_saved_only)
            else -> getString(
                R.string.kokoro_character_detect_result,
                roleLabel(state.role),
                state.occurrences,
            )
        }
        val speaker = currentSpeaker(state)
        val custom = pendingMappings.containsKey(state.name)
        val voiceLabel = voiceLabel(state.role, speaker)
        tvCharacterCurrentVoice.text = if (custom) {
            getString(R.string.kokoro_character_custom_voice, voiceLabel)
        } else {
            getString(
                R.string.kokoro_character_fallback_voice,
                roleLabel(state.role),
                voiceLabel,
            )
        }
        val presets = KokoroOfflineTts.presetSpeakers(state.role)
        val labels = presetLabelResources(state.role)
        presetViews().forEachIndexed { index, view ->
            val preset = presets[index]
            view.text = getString(labels[index])
            view.isSelected = custom && preset == speaker
            view.contentDescription = getString(
                R.string.kokoro_character_preset_accessibility,
                state.name,
                getString(labels[index]),
            )
        }
        tvCharacterPreviewQuote.isVisible = !state.previewText.isNullOrBlank()
        tvCharacterPreviewQuote.text = state.previewText?.let {
            getString(R.string.kokoro_character_preview_quote, it)
        }
        tvCharacterAdvanced.text = getString(R.string.kokoro_voice_advanced_value, speaker)
        rendering = true
        seekCharacterAdvanced.progress = speaker - KokoroOfflineTts.SPEAKER_MIN
        rendering = false
    }

    private fun setSpeaker(rawSpeaker: Int) {
        val state = selectedState() ?: return
        stopPreview(resumeReadAloud = true)
        pendingMappings[state.name] = rawSpeaker.coerceIn(
            KokoroOfflineTts.SPEAKER_MIN,
            KokoroOfflineTts.SPEAKER_MAX,
        )
        renderSelectedCharacter()
    }

    private fun currentSpeaker(state: CharacterState): Int =
        pendingMappings[state.name] ?: KokoroOfflineTts.speakerFor(state.role)

    private fun selectedState(): CharacterState? =
        characters.firstOrNull { it.name == selectedName }

    private fun saveMappings() {
        book.config.kokoroCharacterVoices = KokoroCharacterVoices.sanitize(pendingMappings)
        ReadBook.executor.execute {
            runCatching { book.update() }
        }
        toastOnUi(R.string.kokoro_character_saved)
    }

    private fun previewCurrentVoice() {
        val state = selectedState() ?: return
        if (!KokoroOfflineTts.isInstalled(requireContext())) {
            toastOnUi(R.string.kokoro_model_not_installed)
            return
        }
        stopPreview(resumeReadAloud = true)
        resumeReadAloudAfterPreview = BaseReadAloudService.isRun && !BaseReadAloudService.pause
        if (resumeReadAloudAfterPreview) ReadAloud.pause(requireContext())
        val token = ++previewToken
        val speaker = currentSpeaker(state)
        val sampleText = state.previewText ?: getString(R.string.kokoro_character_preview_text)
        val appContext = requireContext().applicationContext
        setPreviewUi(true)
        previewJob = viewLifecycleOwner.lifecycleScope.launch(IO) {
            var engine: OfflineTts? = null
            var track: AudioTrack? = null
            try {
                engine = KokoroOfflineTts.acquire(appContext)
                val activeEngine = checkNotNull(engine)
                val audio = KokoroOfflineTts.withGeneration {
                    activeEngine.generate(text = sampleText, sid = speaker, speed = 1.0f)
                }
                check(audio.samples.isNotEmpty() && audio.sampleRate > 0)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(audio.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes((audio.samples.size * Float.SIZE_BYTES).coerceAtLeast(4096))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                check(track.state == AudioTrack.STATE_INITIALIZED)
                val written = track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                check(written == audio.samples.size)
                track.play()
                delay(audio.samples.size * 1000L / audio.sampleRate + 180L)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                withContext(Main.immediate) {
                    if (token == previewToken && isAdded) toastOnUi(R.string.kokoro_voice_preview_failed)
                }
            } finally {
                track?.let {
                    runCatching { it.stop() }
                    runCatching { it.flush() }
                    runCatching { it.release() }
                }
                engine?.let(KokoroOfflineTts::release)
                withContext(NonCancellable + Main.immediate) {
                    if (token == previewToken) {
                        previewJob = null
                        if (isAdded && this@KokoroCharacterVoiceDialog.view != null) setPreviewUi(false)
                        resumeReadAloudIfNeeded()
                    }
                }
            }
        }
    }

    private fun stopPreview(resumeReadAloud: Boolean) {
        previewToken++
        previewJob?.cancel()
        previewJob = null
        if (view != null) setPreviewUi(false)
        if (resumeReadAloud) resumeReadAloudIfNeeded()
    }

    private fun setPreviewUi(playing: Boolean) {
        binding.tvCharacterPreview.isSelected = playing
        binding.tvCharacterPreview.text = getString(
            if (playing) R.string.kokoro_voice_previewing else R.string.kokoro_voice_preview
        )
    }

    private fun resumeReadAloudIfNeeded() {
        if (!resumeReadAloudAfterPreview) return
        resumeReadAloudAfterPreview = false
        if (isAdded && BaseReadAloudService.isRun && BaseReadAloudService.pause) {
            ReadAloud.resume(requireContext())
        }
    }

    private fun roleLabel(role: SpeechRole): String = getString(
        when (role) {
            SpeechRole.MALE -> R.string.kokoro_role_male
            SpeechRole.FEMALE -> R.string.kokoro_role_female
            SpeechRole.NARRATOR -> R.string.kokoro_role_narrator
            SpeechRole.UNKNOWN_DIALOGUE -> R.string.kokoro_role_unknown
        }
    )

    private fun voiceLabel(role: SpeechRole, speaker: Int): String {
        val presets = KokoroOfflineTts.presetSpeakers(role)
        val index = presets.indexOf(speaker)
        return if (index >= 0) {
            getString(presetLabelResources(role)[index])
        } else {
            getString(R.string.kokoro_voice_custom_friendly)
        }
    }

    private fun presetLabelResources(role: SpeechRole): IntArray = when (role) {
        SpeechRole.NARRATOR -> intArrayOf(
            R.string.kokoro_preset_narrator_steady,
            R.string.kokoro_preset_narrator_clear,
            R.string.kokoro_preset_narrator_soft,
            R.string.kokoro_preset_narrator_calm,
        )
        SpeechRole.MALE -> intArrayOf(
            R.string.kokoro_preset_male_steady,
            R.string.kokoro_preset_male_young,
            R.string.kokoro_preset_male_clear,
            R.string.kokoro_preset_male_deep,
        )
        SpeechRole.FEMALE -> intArrayOf(
            R.string.kokoro_preset_female_soft,
            R.string.kokoro_preset_female_clear,
            R.string.kokoro_preset_female_calm,
            R.string.kokoro_preset_female_light,
        )
        SpeechRole.UNKNOWN_DIALOGUE -> intArrayOf(
            R.string.kokoro_preset_dialogue_natural,
            R.string.kokoro_preset_dialogue_clear,
            R.string.kokoro_preset_dialogue_soft,
            R.string.kokoro_preset_dialogue_lively,
        )
    }

    private fun renderAdvanced(expanded: Boolean) = binding.run {
        llCharacterAdvancedDetail.isVisible = expanded
        tvCharacterAdvancedToggle.setText(
            if (expanded) R.string.kokoro_voice_advanced_collapse
            else R.string.kokoro_voice_advanced_expand
        )
    }

    private fun presetViews(): List<TextView> = binding.run {
        listOf(
            tvCharacterPreset1,
            tvCharacterPreset2,
            tvCharacterPreset3,
            tvCharacterPreset4,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
