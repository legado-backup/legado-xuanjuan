package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.FastVitsOfflineTts
import io.legado.app.help.tts.KokoroOfflineTts
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding


class ReadAloudDialog : BaseDialogFragment(R.layout.dialog_read_aloud),
    SpeakEngineDialog.CallBack,
    SleepTimerDialog.CallBack {
    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        if (AppConfig.isEInkMode) {
            val bg = requireContext().bottomBackground
            val textColor = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(bg))
            binding.rootView.setBackgroundColor(bg)
            listOf(
                binding.llEngine,
                binding.llTimer,
                binding.llSpeedCard,
                binding.llBackToSpeech,
            ).forEach { it.setBackgroundColor(Color.TRANSPARENT) }
            binding.ivPlayPause.setBackgroundColor(Color.TRANSPARENT)
            applyEInkColors(binding.rootView, textColor)
        } else {
            binding.rootView.setBackgroundResource(R.drawable.xuanjuan_read_aloud_bg)
        }
        initData()
        initEvent()
    }

    private fun applyEInkColors(view: View, color: Int) {
        when (view) {
            is TextView -> view.setTextColor(color)
            is ImageView -> view.setColorFilter(color)
            is ViewGroup -> for (index in 0 until view.childCount) {
                applyEInkColors(view.getChildAt(index), color)
            }
        }
    }

    private fun initData() = binding.run {
        when (ReadAloud.ttsEngine) {
            KokoroOfflineTts.ENGINE_TOKEN -> if (KokoroOfflineTts.isInstalled(requireContext())) {
                KokoroOfflineTts.prewarm(requireContext())
            }
            FastVitsOfflineTts.ENGINE_TOKEN -> if (FastVitsOfflineTts.isInstalled(requireContext())) {
                FastVitsOfflineTts.prewarm(requireContext())
            }
        }
        upPlayState()
        upEngineName()
        upStopText()
        refreshSpeedControls()
        upBackToSpeechVisibility()
    }

    private fun initEvent() = binding.run {
        llMainMenu.setOnClickListener {
            callBack?.showMenuBar()
            dismissAllowingStateLoss()
        }
        llSetting.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        llEngine.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
        tvPre.setOnClickListener {
            if (ReadAloud.followReadAloudPosition) {
                ReadAloud.prevChapter(requireContext())
            } else {
                ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            }
        }
        tvNext.setOnClickListener {
            if (ReadAloud.followReadAloudPosition) {
                ReadAloud.nextChapter(requireContext())
            } else {
                ReadBook.moveToNextChapter(upContent = true)
            }
        }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        llCatalog.setOnClickListener { callBack?.openChapterList() }
        llToBackstage.setOnClickListener { callBack?.finish() }
        llBackToSpeech.setOnClickListener {
            callBack?.backToSpeakingPosition()
            dismissAllowingStateLoss()
        }
        llTimer.setOnClickListener {
            showDialogFragment(
                SleepTimerDialog.newInstance(
                    BaseReadAloudService.timeMinute,
                    BaseReadAloudService.chapterToStop,
                )
            )
        }
        cbTtsFollowSys.setOnCheckedChangeListener { _, isChecked ->
            if (isOfflineNeuralEngine()) return@setOnCheckedChangeListener
            AppConfig.ttsFlowSys = isChecked
            upTtsSpeechRateEnabled(!isChecked)
            upTtsSpeechRate()
        }
        ivTtsSpeechReduce.setOnClickListener {
            setSpeechRate(activeSpeechRate() - 1)
        }
        ivTtsSpeechAdd.setOnClickListener {
            setSpeechRate(activeSpeechRate() + 1)
        }
        tvSpeed08.setOnClickListener { setSpeechRate(3) }
        tvSpeed10.setOnClickListener { setSpeechRate(5) }
        tvSpeed12.setOnClickListener { setSpeechRate(7) }
        tvSpeed15.setOnClickListener { setSpeechRate(10) }
        tvSpeed20.setOnClickListener { setSpeechRate(15) }
        seekTtsSpeechRate.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                super.onProgressChanged(seekBar, progress, fromUser)
                upTtsSpeechRateText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                setSpeechRate(seekBar.progress)
            }
        })
    }

    private fun upTtsSpeechRateEnabled(enabled: Boolean) {
        binding.run {
            upTtsSpeechRateText(activeSpeechRate())
            seekTtsSpeechRate.isEnabled = enabled
            ivTtsSpeechReduce.isEnabled = enabled
            ivTtsSpeechAdd.isEnabled = enabled
            val alpha = if (enabled) 1f else 0.38f
            listOf(
                tvTtsSpeedValue,
                seekTtsSpeechRate,
                ivTtsSpeechReduce,
                ivTtsSpeechAdd,
                tvSpeed08,
                tvSpeed10,
                tvSpeed12,
                tvSpeed15,
                tvSpeed20,
            ).forEach {
                it.isEnabled = enabled
                it.alpha = alpha
            }
        }
    }

    private fun upPlayState() {
        if (!BaseReadAloudService.pause) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
        } else {
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
        }
        TooltipCompat.setTooltipText(
            binding.ivPlayPause,
            binding.ivPlayPause.contentDescription,
        )
        upBackToSpeechVisibility()
    }

    private fun upStopText() {
        binding.tvTimer.text = when {
            BaseReadAloudService.chapterToStop > 0 -> getString(
                R.string.read_aloud_timer_status_chapter,
                BaseReadAloudService.chapterToStop,
            )

            BaseReadAloudService.timeMinute > 0 -> getString(
                R.string.sleep_timer_status_time,
                BaseReadAloudService.timeMinute,
            )

            else -> getString(R.string.sleep_timer_status_none)
        }
    }

    private fun upTtsSpeechRateText(value: Int) {
        val safeValue = value.coerceIn(AppConfig.minTtsSpeechRate, AppConfig.maxTtsSpeechRate)
        binding.tvTtsSpeedValue.text = getString(
            R.string.tts_speed_value,
            (safeValue + 5) / 10f,
        )
        binding.tvSpeed08.isSelected = safeValue == 3
        binding.tvSpeed10.isSelected = safeValue == 5
        binding.tvSpeed12.isSelected = safeValue == 7
        binding.tvSpeed15.isSelected = safeValue == 10
        binding.tvSpeed20.isSelected = safeValue == 15
        binding.tvSpeed15.text = getString(
            if (isOfflineNeuralEngine()) R.string.tts_speed_15x_recommended else R.string.tts_speed_15x
        )
    }

    private fun setSpeechRate(value: Int) {
        val safeValue = value.coerceIn(AppConfig.minTtsSpeechRate, AppConfig.maxTtsSpeechRate)
        if (isOfflineNeuralEngine()) {
            AppConfig.kokoroSpeechRate = safeValue
        } else {
            AppConfig.ttsSpeechRate = safeValue
        }
        if (binding.seekTtsSpeechRate.progress != safeValue) {
            binding.seekTtsSpeechRate.progress = safeValue
        }
        upTtsSpeechRateText(safeValue)
        upTtsSpeechRate()
    }

    private fun activeSpeechRate(): Int =
        if (isOfflineNeuralEngine()) AppConfig.kokoroSpeechRate else AppConfig.ttsSpeechRate

    private fun isOfflineNeuralEngine(): Boolean =
        ReadAloud.ttsEngine == KokoroOfflineTts.ENGINE_TOKEN ||
            ReadAloud.ttsEngine == FastVitsOfflineTts.ENGINE_TOKEN

    private fun refreshSpeedControls() = binding.run {
        val offlineNeural = isOfflineNeuralEngine()
        cbTtsFollowSys.visible(!offlineNeural)
        if (!offlineNeural) {
            cbTtsFollowSys.isChecked = requireContext().getPrefBoolean("ttsFollowSys", true)
        }
        val rate = activeSpeechRate()
        if (seekTtsSpeechRate.progress != rate) {
            seekTtsSpeechRate.progress = rate
        }
        upTtsSpeechRateText(rate)
        upTtsSpeechRateEnabled(offlineNeural || !cbTtsFollowSys.isChecked)
    }

    private fun upBackToSpeechVisibility() {
        binding.llBackToSpeech.visible(
            BaseReadAloudService.isRun && !ReadAloud.followReadAloudPosition
        )
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    private fun upEngineName() {
        val engineName = ReadAloud.getEngineName(requireContext())
        binding.tvEngineName.text = engineName
        binding.llEngine.contentDescription = "${getString(R.string.speak_engine)}: $engineName"
    }

    private fun upOfflineStartupStatus(status: Int, engineToken: String) {
        if (ReadAloud.ttsEngine != engineToken) return
        val statusText = when (engineToken) {
            FastVitsOfflineTts.ENGINE_TOKEN -> when (status) {
                FastVitsOfflineTts.PLAYBACK_STATUS_LOADING_MODEL ->
                    getString(R.string.fast_vits_playback_loading_model)
                FastVitsOfflineTts.PLAYBACK_STATUS_FIRST_AUDIO ->
                    getString(R.string.fast_vits_playback_first_audio)
                else -> null
            }
            else -> when (status) {
                KokoroOfflineTts.PLAYBACK_STATUS_LOADING_MODEL ->
                    getString(R.string.kokoro_playback_loading_model)
                KokoroOfflineTts.PLAYBACK_STATUS_FIRST_AUDIO ->
                    getString(R.string.kokoro_playback_first_audio)
                else -> null
            }
        } ?: run {
            upEngineName()
            return
        }
        binding.tvEngineName.text = statusText
        binding.llEngine.contentDescription = "${getString(R.string.speak_engine)}: $statusText"
    }

    override fun upSpeakEngineSummary() {
        upEngineName()
        refreshSpeedControls()
    }

    override fun onSleepTimerMinute(minute: Int) {
        ReadAloud.setTimer(requireContext(), minute)
    }

    override fun onSleepTimerChapter(count: Int) {
        ReadAloud.setChapterStop(requireContext(), count)
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            upPlayState()
            upBackToSpeechVisibility()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) {
            upStopText()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_CHAPTER_STOP) {
            upStopText()
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_FOLLOW) { upBackToSpeechVisibility() }
        observeEvent<Int>(KokoroOfflineTts.PLAYBACK_STATUS_EVENT) {
            upOfflineStartupStatus(it, KokoroOfflineTts.ENGINE_TOKEN)
        }
        observeEvent<Int>(FastVitsOfflineTts.PLAYBACK_STATUS_EVENT) {
            upOfflineStartupStatus(it, FastVitsOfflineTts.ENGINE_TOKEN)
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun backToSpeakingPosition()
        fun finish()
    }
}
