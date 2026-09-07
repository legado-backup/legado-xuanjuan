package io.legado.app.ui.book.read.config

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.OfflineTts
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogKokoroVoiceBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.KokoroOfflineTts
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

class KokoroVoiceDialog : BaseDialogFragment(R.layout.dialog_kokoro_voice) {

    companion object {
        private const val ARG_AUTO_PREVIEW = "autoPreview"

        fun newInstance(autoPreview: Boolean = false) = KokoroVoiceDialog().apply {
            arguments = Bundle().apply { putBoolean(ARG_AUTO_PREVIEW, autoPreview) }
        }
    }

    private data class VoicePreset(
        val speaker: Int,
        @StringRes val labelRes: Int,
    )

    private val binding by viewBinding(DialogKokoroVoiceBinding::bind)
    private val pendingSpeakers = mutableMapOf<SpeechRole, Int>()
    private var selectedRole = SpeechRole.NARRATOR
    private var rendering = false
    private var previewJob: Job? = null
    private var previewToken = 0L
    private var resumeReadAloudAfterPreview = false

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        pendingSpeakers[SpeechRole.NARRATOR] = AppConfig.kokoroNarratorSpeaker
        pendingSpeakers[SpeechRole.MALE] = AppConfig.kokoroMaleSpeaker
        pendingSpeakers[SpeechRole.FEMALE] = AppConfig.kokoroFemaleSpeaker
        pendingSpeakers[SpeechRole.UNKNOWN_DIALOGUE] = AppConfig.kokoroUnknownSpeaker
        KokoroOfflineTts.prewarm(requireContext())
        bindRoleButtons()
        bindActions()
        bindAdvancedSlider()
        renderRole()
        renderAdvanced(false)
        if (arguments?.getBoolean(ARG_AUTO_PREVIEW) == true) {
            binding.root.post { if (isAdded) previewCurrentVoice() }
        }
    }

    override fun onDestroyView() {
        stopPreview(resumeReadAloud = true)
        super.onDestroyView()
    }

    private fun bindRoleButtons() = binding.run {
        tvRoleNarrator.setOnClickListener { selectRole(SpeechRole.NARRATOR) }
        tvRoleMale.setOnClickListener { selectRole(SpeechRole.MALE) }
        tvRoleFemale.setOnClickListener { selectRole(SpeechRole.FEMALE) }
        tvRoleUnknown.setOnClickListener { selectRole(SpeechRole.UNKNOWN_DIALOGUE) }
    }

    private fun bindActions() = binding.run {
        presetViews().forEachIndexed { index, presetView ->
            presetView.setOnClickListener {
                presetsFor(selectedRole).getOrNull(index)?.let { preset ->
                    setPendingSpeaker(preset.speaker)
                }
            }
        }
        tvPreview.setOnClickListener { previewCurrentVoice() }
        tvAdvancedToggle.setOnClickListener {
            renderAdvanced(!llAdvancedDetail.isVisible)
        }
        tvCharacterVoices.setOnClickListener {
            if (ReadBook.book == null) {
                toastOnUi(R.string.kokoro_character_no_book)
            } else {
                KokoroCharacterVoiceDialog().show(
                    childFragmentManager,
                    KokoroCharacterVoiceDialog::class.simpleName,
                )
            }
        }
        tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        tvSave.setOnClickListener {
            saveSelections()
            dismissAllowingStateLoss()
        }
    }

    private fun bindAdvancedSlider() {
        binding.seekAdvanced.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!rendering && fromUser) {
                    val range = KokoroOfflineTts.speakerRange(selectedRole)
                    setPendingSpeaker(range.first + progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun selectRole(role: SpeechRole) {
        if (selectedRole == role) return
        stopPreview(resumeReadAloud = true)
        selectedRole = role
        renderRole()
    }

    private fun renderRole() = binding.run {
        rendering = true
        roleViews().forEach { (role, roleView) -> roleView.isSelected = role == selectedRole }
        val presets = presetsFor(selectedRole)
        presetViews().forEachIndexed { index, presetView ->
            val preset = presets[index]
            val label = getString(preset.labelRes)
            presetView.text = label
            presetView.contentDescription = getString(
                R.string.kokoro_voice_preset_accessibility,
                getString(roleTitleRes(selectedRole)),
                label,
            )
        }
        val range = KokoroOfflineTts.speakerRange(selectedRole)
        seekAdvanced.max = range.last - range.first
        seekAdvanced.progress = currentSpeaker() - range.first
        rendering = false
        renderSelection()
    }

    private fun renderSelection() = binding.run {
        val speaker = currentSpeaker()
        val presets = presetsFor(selectedRole)
        presetViews().forEachIndexed { index, presetView ->
            presetView.isSelected = presets[index].speaker == speaker
        }
        val matchedPreset = presets.firstOrNull { it.speaker == speaker }
        tvCurrentVoice.text = if (matchedPreset != null) {
            getString(
                R.string.kokoro_voice_current_preset,
                getString(roleTitleRes(selectedRole)),
                getString(matchedPreset.labelRes),
            )
        } else {
            getString(
                R.string.kokoro_voice_current_custom,
                getString(roleTitleRes(selectedRole)),
            )
        }
        tvAdvanced.text = getString(R.string.kokoro_voice_advanced_value, speaker)
        val range = KokoroOfflineTts.speakerRange(selectedRole)
        val expectedProgress = speaker - range.first
        if (seekAdvanced.progress != expectedProgress) {
            rendering = true
            seekAdvanced.progress = expectedProgress
            rendering = false
        }
    }

    private fun renderAdvanced(expanded: Boolean) = binding.run {
        llAdvancedDetail.isVisible = expanded
        tvAdvancedToggle.setText(
            if (expanded) R.string.kokoro_voice_advanced_collapse
            else R.string.kokoro_voice_advanced_expand
        )
    }

    private fun setPendingSpeaker(value: Int) {
        stopPreview(resumeReadAloud = true)
        pendingSpeakers[selectedRole] = KokoroOfflineTts.clampSpeaker(selectedRole, value)
        renderSelection()
    }

    private fun saveSelections() {
        AppConfig.kokoroNarratorSpeaker = pendingSpeakers.getValue(SpeechRole.NARRATOR)
        AppConfig.kokoroMaleSpeaker = pendingSpeakers.getValue(SpeechRole.MALE)
        AppConfig.kokoroFemaleSpeaker = pendingSpeakers.getValue(SpeechRole.FEMALE)
        AppConfig.kokoroUnknownSpeaker = pendingSpeakers.getValue(SpeechRole.UNKNOWN_DIALOGUE)
        toastOnUi(R.string.kokoro_voice_saved)
    }

    private fun previewCurrentVoice() {
        if (!KokoroOfflineTts.isInstalled(requireContext())) {
            toastOnUi(R.string.kokoro_model_not_installed)
            return
        }
        stopPreview(resumeReadAloud = true)
        resumeReadAloudAfterPreview = BaseReadAloudService.isRun && !BaseReadAloudService.pause
        if (resumeReadAloudAfterPreview) {
            ReadAloud.pause(requireContext())
        }
        val token = ++previewToken
        val speaker = currentSpeaker()
        val sampleText = getString(previewTextRes(selectedRole))
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
                check(audio.samples.isNotEmpty() && audio.sampleRate > 0) {
                    "Kokoro preview returned empty audio"
                }
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
                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "Kokoro preview AudioTrack init failed"
                }
                val written = track.write(
                    audio.samples,
                    0,
                    audio.samples.size,
                    AudioTrack.WRITE_BLOCKING,
                )
                check(written == audio.samples.size) {
                    "Kokoro preview AudioTrack write failed: $written/${audio.samples.size}"
                }
                track.play()
                delay(audio.samples.size * 1000L / audio.sampleRate + 180L)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                withContext(Main.immediate) {
                    if (token == previewToken && isAdded) {
                        toastOnUi(R.string.kokoro_voice_preview_failed)
                    }
                }
            } finally {
                track?.let { audioTrack ->
                    runCatching { audioTrack.stop() }
                    runCatching { audioTrack.flush() }
                    runCatching { audioTrack.release() }
                }
                engine?.let(KokoroOfflineTts::release)
                withContext(NonCancellable + Main.immediate) {
                    if (token == previewToken) {
                        previewJob = null
                        if (isAdded && this@KokoroVoiceDialog.view != null) {
                            setPreviewUi(false)
                        }
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

    private fun resumeReadAloudIfNeeded() {
        if (!resumeReadAloudAfterPreview) return
        resumeReadAloudAfterPreview = false
        if (isAdded && BaseReadAloudService.isRun && BaseReadAloudService.pause) {
            ReadAloud.resume(requireContext())
        }
    }

    private fun setPreviewUi(playing: Boolean) = binding.run {
        tvPreview.isSelected = playing
        tvPreview.text = getString(
            if (playing) R.string.kokoro_voice_previewing else R.string.kokoro_voice_preview
        )
        tvPreview.contentDescription = tvPreview.text
    }

    private fun currentSpeaker(): Int = pendingSpeakers.getValue(selectedRole)

    private fun presetsFor(role: SpeechRole): List<VoicePreset> {
        val labels = when (role) {
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
        return KokoroOfflineTts.presetSpeakers(role).mapIndexed { index, speaker ->
            VoicePreset(speaker, labels[index])
        }
    }

    @StringRes
    private fun roleTitleRes(role: SpeechRole): Int = when (role) {
        SpeechRole.NARRATOR -> R.string.kokoro_role_narrator
        SpeechRole.MALE -> R.string.kokoro_role_male
        SpeechRole.FEMALE -> R.string.kokoro_role_female
        SpeechRole.UNKNOWN_DIALOGUE -> R.string.kokoro_role_unknown
    }

    @StringRes
    private fun previewTextRes(role: SpeechRole): Int = when (role) {
        SpeechRole.NARRATOR -> R.string.kokoro_preview_text_narrator
        SpeechRole.MALE -> R.string.kokoro_preview_text_male
        SpeechRole.FEMALE -> R.string.kokoro_preview_text_female
        SpeechRole.UNKNOWN_DIALOGUE -> R.string.kokoro_preview_text_unknown
    }

    private fun roleViews(): List<Pair<SpeechRole, TextView>> = binding.run {
        listOf(
            SpeechRole.NARRATOR to tvRoleNarrator,
            SpeechRole.MALE to tvRoleMale,
            SpeechRole.FEMALE to tvRoleFemale,
            SpeechRole.UNKNOWN_DIALOGUE to tvRoleUnknown,
        )
    }

    private fun presetViews(): List<TextView> = binding.run {
        listOf(tvPreset1, tvPreset2, tvPreset3, tvPreset4)
    }
}
