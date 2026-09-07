package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.view.isVisible
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemHttpTtsBinding
import io.legado.app.databinding.ItemOfflineTtsEngineBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.FastVitsOfflineTts
import io.legado.app.help.tts.KokoroOfflineTts
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.FastVitsModelInstallService
import io.legado.app.service.KokoroModelInstallService
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun HttpTTS.hasLoginCapability(): Boolean {
    return !loginUrl.isNullOrBlank() || !loginUi.isNullOrBlank()
}

internal fun HttpTTS.shouldOpenLoginOnSelection(): Boolean {
    return hasLoginCapability()
}

/**
 * tts引擎管理
 */
class SpeakEngineDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel: SpeakEngineViewModel by viewModels()
    private val ttsUrlKey = "ttsUrlKey"
    private val adapter by lazy { Adapter(requireContext()) }
    private var ttsEngine: String? = ReadAloud.ttsEngine
    private val sysTtsViews = arrayListOf<RadioButton>()
    private var fastVitsRadio: RadioButton? = null
    private var fastVitsHeaderBinding: ItemOfflineTtsEngineBinding? = null
    private var offlineTtsRadio: RadioButton? = null
    private var offlineHeaderBinding: ItemOfflineTtsEngineBinding? = null
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private var currentSelect = -1
    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportHttpTtsDialog(uri.toString()))
        }
    }
    private val exportDirResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                    sourceSharePassphraseButton(
                        layoutInflater,
                        url,
                        SourceSharePassphrase.Type.TTS_RULE,
                    )
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(url)
                }
                customView { alertBinding.root }
                okButton {
                    requireContext().sendToClip(url)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initMenu()
        initData()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.speak_engine)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemOfflineTtsEngineBinding.inflate(layoutInflater, recyclerView, false).apply {
                fastVitsHeaderBinding = this
                fastVitsRadio = cbName
                cbName.setText(R.string.fast_vits_offline_engine)
                cbName.tag = FastVitsOfflineTts.ENGINE_TOKEN
                cbName.isChecked = ttsEngine == FastVitsOfflineTts.ENGINE_TOKEN
                cbName.setOnClickListener {
                    if (FastVitsOfflineTts.isInstalled(requireContext())) {
                        upTts(FastVitsOfflineTts.ENGINE_TOKEN)
                    } else if (!fastVitsInstallActive()) {
                        cbName.isChecked = false
                        startFastVitsModelInstall()
                    } else {
                        cbName.isChecked = false
                    }
                }
                tvAction.setOnClickListener {
                    when {
                        fastVitsInstallActive() -> {
                            FastVitsModelInstallService.stop(requireContext())
                            FastVitsOfflineTts.updateModelInstallSnapshot(
                                FastVitsOfflineTts.ModelInstallSnapshot(
                                    FastVitsOfflineTts.ModelInstallPhase.CANCELED,
                                )
                            )
                            updateFastVitsHeader(this)
                        }
                        FastVitsOfflineTts.isInstalled(requireContext()) ->
                            upTts(FastVitsOfflineTts.ENGINE_TOKEN)
                        else -> startFastVitsModelInstall()
                    }
                }
                tvPreview.gone()
                tvModelManage.setOnClickListener { showFastVitsModelMenu(tvModelManage) }
                updateFastVitsHeader(this)
            }
        }
        adapter.addHeaderView {
            ItemOfflineTtsEngineBinding.inflate(layoutInflater, recyclerView, false).apply {
                offlineHeaderBinding = this
                offlineTtsRadio = cbName
                cbName.tag = KokoroOfflineTts.ENGINE_TOKEN
                cbName.isChecked = ttsEngine == KokoroOfflineTts.ENGINE_TOKEN
                cbName.setOnClickListener {
                    if (KokoroOfflineTts.isInstalled(requireContext())) {
                        upTts(KokoroOfflineTts.ENGINE_TOKEN)
                    } else if (!offlineInstallActive()) {
                        cbName.isChecked = false
                        startOfflineModelInstall()
                    } else {
                        cbName.isChecked = false
                    }
                }
                tvAction.setOnClickListener {
                    when {
                        offlineInstallActive() -> {
                            KokoroModelInstallService.stop(requireContext())
                            KokoroOfflineTts.updateModelInstallSnapshot(
                                KokoroOfflineTts.ModelInstallSnapshot(
                                    KokoroOfflineTts.ModelInstallPhase.CANCELED,
                                )
                            )
                            updateOfflineHeader(this)
                        }
                        KokoroOfflineTts.isInstalled(requireContext()) ->
                            showDialogFragment(KokoroVoiceDialog())
                        else -> startOfflineModelInstall()
                    }
                }
                tvPreview.setOnClickListener {
                    showDialogFragment(KokoroVoiceDialog.newInstance(autoPreview = true))
                }
                tvModelManage.setOnClickListener { showOfflineModelMenu(tvModelManage) }
                updateOfflineHeader(this)
            }
        }
        adapter.addHeaderView {
            ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                sysTtsViews.add(cbName)
                ivEdit.gone()
                ivMenuDelete.gone()
                labelSys.visible()
                cbName.text = "系统默认"
                cbName.tag = ""
                cbName.isChecked = ttsEngine == null || ttsEngine!!.isJsonObject()
                        && GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                    .getOrNull()?.value.isNullOrEmpty()
                cbName.setOnClickListener {
                    upTts(GSON.toJson(SelectItem("系统默认", "")))
                }
            }
        }
        viewModel.sysEngines.forEach { engine ->
            adapter.addHeaderView {
                ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                    sysTtsViews.add(cbName)
                    ivEdit.gone()
                    ivMenuDelete.gone()
                    labelSys.visible()
                    cbName.text = engine.label
                    cbName.tag = engine.name
                    cbName.isChecked = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                        .getOrNull()?.value == cbName.tag
                    cbName.setOnClickListener {
                        upTts(GSON.toJson(SelectItem(engine.label, engine.name)))
                    }
                }
            }
        }
        tvFooterLeft.setText(R.string.book)
        tvFooterLeft.visible()
        tvFooterLeft.setOnClickListener {
            ReadBook.book?.setTtsEngine(ttsEngine)
            callBack?.upSpeakEngineSummary()
            ReadAloud.upReadAloudClass()
            dismissAllowingStateLoss()
        }
        tvOk.setText(R.string.general)
        tvOk.visible()
        tvOk.setOnClickListener {
            ReadBook.book?.setTtsEngine(null)
            AppConfig.ttsEngine = ttsEngine
            callBack?.upSpeakEngineSummary()
            ReadAloud.upReadAloudClass()
            dismissAllowingStateLoss()
        }
        tvCancel.visible()
        tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    private fun initMenu() = binding.run {
        toolBar.inflateMenu(R.menu.speak_engine)
        toolBar.menu.applyTint(requireContext())
        toolBar.setOnMenuItemClickListener(this@SpeakEngineDialog)
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.httpTTSDao.flowAll().catch {
                AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                adapter.setItems(it)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> clearCache()
            R.id.menu_add -> showDialogFragment<HttpTtsEditDialog>()
            R.id.menu_default -> viewModel.importDefault()
            R.id.menu_import_local -> importDocResult.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> importAlert()
            R.id.menu_export_all -> exportDirResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "httpTts.json",
                    GSON.toJson(adapter.getItems()).toByteArray(),
                    "application/json"
                )
            }
            R.id.menu_export -> {
                if (currentSelect == -1) {
                    toastOnUi(R.string.is_system_tts_no_export)
                    return true
                }
                val tts = adapter.getItem(currentSelect) ?: return true
                exportDirResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "httpTts_${tts.name}.json",
                        GSON.toJson(tts).toByteArray(),
                        "application/json"
                    )
                }
            }
        }
        return true
    }

    fun clearCache() {
        execute {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${requireContext().cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            toastOnUi(R.string.clear_cache_success)
        }
    }

    private fun importAlert() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(ttsUrlKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    if (url.isAbsUrl() && !cacheUrls.contains(url)) {
                        cacheUrls.add(0, url)
                        aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportHttpTtsDialog(url))
                }
            }
        }
    }

    private fun upTts(tts: String) {
        if (tts == KokoroOfflineTts.ENGINE_TOKEN && !KokoroOfflineTts.isInstalled(requireContext())) {
            return
        }
        if (tts == FastVitsOfflineTts.ENGINE_TOKEN && !FastVitsOfflineTts.isInstalled(requireContext())) {
            return
        }
        ttsEngine = tts
        when (tts) {
            KokoroOfflineTts.ENGINE_TOKEN -> KokoroOfflineTts.prewarm(requireContext())
            FastVitsOfflineTts.ENGINE_TOKEN -> FastVitsOfflineTts.prewarm(requireContext())
        }
        offlineTtsRadio?.isChecked = tts == KokoroOfflineTts.ENGINE_TOKEN
        fastVitsRadio?.isChecked = tts == FastVitsOfflineTts.ENGINE_TOKEN
        if (tts == KokoroOfflineTts.ENGINE_TOKEN || tts == FastVitsOfflineTts.ENGINE_TOKEN) {
            currentSelect = -1
        }
        sysTtsViews.forEach {
            val isChecked = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                .getOrNull()?.value == it.tag
            if (isChecked) {
                currentSelect = -1
            }
            it.isChecked = isChecked
        }
        adapter.notifyItemRangeChanged(adapter.getHeaderCount(), adapter.itemCount)
    }

    private fun startFastVitsModelInstall(force: Boolean = false) {
        FastVitsOfflineTts.updateModelInstallSnapshot(
            FastVitsOfflineTts.ModelInstallSnapshot(
                FastVitsOfflineTts.ModelInstallPhase.PREPARING,
            )
        )
        FastVitsModelInstallService.start(requireContext(), force)
        toastOnUi(R.string.fast_vits_model_install_started)
        fastVitsHeaderBinding?.let(::updateFastVitsHeader)
    }

    private fun updateFastVitsHeader(header: ItemOfflineTtsEngineBinding) {
        val installed = FastVitsOfflineTts.isInstalled(requireContext())
        val snapshot = FastVitsOfflineTts.modelInstallSnapshot()
        val active = snapshot.phase in setOf(
            FastVitsOfflineTts.ModelInstallPhase.PREPARING,
            FastVitsOfflineTts.ModelInstallPhase.DOWNLOADING,
            FastVitsOfflineTts.ModelInstallPhase.EXTRACTING,
        )
        header.pbModelProgress.isVisible = active
        header.llOfflineActions.isVisible = installed
        header.tvPreview.gone()
        header.tvModelManage.isVisible = installed && !active
        when {
            active -> {
                header.tvAction.setText(R.string.fast_vits_model_cancel_download)
                when (snapshot.phase) {
                    FastVitsOfflineTts.ModelInstallPhase.DOWNLOADING -> {
                        header.pbModelProgress.isIndeterminate = snapshot.progress < 0
                        header.pbModelProgress.progress = snapshot.progress.coerceAtLeast(0)
                        header.tvSummary.text = if (snapshot.totalBytes > 0L) {
                            getString(
                                R.string.fast_vits_model_downloading_detail,
                                snapshot.progress.coerceAtLeast(0),
                                Formatter.formatShortFileSize(requireContext(), snapshot.downloadedBytes),
                                Formatter.formatShortFileSize(requireContext(), snapshot.totalBytes),
                            )
                        } else {
                            getString(
                                R.string.fast_vits_model_downloading_percent,
                                snapshot.progress.coerceAtLeast(0),
                            )
                        }
                    }
                    FastVitsOfflineTts.ModelInstallPhase.EXTRACTING -> {
                        header.pbModelProgress.isIndeterminate = true
                        header.tvSummary.setText(R.string.fast_vits_model_extracting)
                    }
                    else -> {
                        header.pbModelProgress.isIndeterminate = true
                        header.tvSummary.setText(R.string.fast_vits_model_preparing)
                    }
                }
            }
            installed -> {
                header.pbModelProgress.isIndeterminate = false
                header.pbModelProgress.progress = 100
                header.tvSummary.text = getString(
                    R.string.fast_vits_model_installed_summary_size,
                    Formatter.formatShortFileSize(
                        requireContext(),
                        FastVitsOfflineTts.modelSizeBytes(requireContext()),
                    ),
                )
                header.tvAction.setText(R.string.fast_vits_model_ready_action)
            }
            snapshot.phase == FastVitsOfflineTts.ModelInstallPhase.FAILED -> {
                header.tvSummary.setText(R.string.fast_vits_model_failed_summary)
                header.tvAction.setText(R.string.fast_vits_model_retry_download)
            }
            snapshot.phase == FastVitsOfflineTts.ModelInstallPhase.CANCELED -> {
                header.tvSummary.setText(R.string.fast_vits_model_canceled_summary)
                header.tvAction.setText(R.string.fast_vits_model_retry_download)
            }
            else -> {
                header.tvSummary.setText(R.string.fast_vits_model_not_installed)
                header.tvAction.setText(R.string.fast_vits_model_download)
            }
        }
        header.cbName.isEnabled = installed
        if (!installed && ttsEngine == FastVitsOfflineTts.ENGINE_TOKEN) {
            header.cbName.isChecked = false
        }
    }

    private fun fastVitsInstallActive(): Boolean =
        FastVitsOfflineTts.modelInstallSnapshot().phase in setOf(
            FastVitsOfflineTts.ModelInstallPhase.PREPARING,
            FastVitsOfflineTts.ModelInstallPhase.DOWNLOADING,
            FastVitsOfflineTts.ModelInstallPhase.EXTRACTING,
        )

    private fun showFastVitsModelMenu(anchor: View) {
        val sizeText = Formatter.formatShortFileSize(
            requireContext(),
            FastVitsOfflineTts.modelSizeBytes(requireContext()),
        )
        popupActionMenu(requireContext()) {
            item(getString(R.string.fast_vits_model_reinstall), "reinstall")
            item(getString(R.string.fast_vits_model_delete_with_size, sizeText), "delete")
        }.show(anchor) { action ->
            when (action) {
                "reinstall" -> startFastVitsModelInstall(force = true)
                "delete" -> confirmDeleteFastVitsModel(sizeText)
            }
        }
    }

    private fun confirmDeleteFastVitsModel(sizeText: String) {
        alert(R.string.fast_vits_model_manage) {
            setMessage(getString(R.string.fast_vits_model_delete_confirm, sizeText))
            noButton()
            yesButton {
                lifecycleScope.launch {
                    if (ReadAloud.ttsEngine == FastVitsOfflineTts.ENGINE_TOKEN &&
                        BaseReadAloudService.isRun
                    ) {
                        ReadAloud.stop(requireContext())
                        delay(300)
                    }
                    val deleted = withContext(IO) {
                        FastVitsOfflineTts.deleteModel(requireContext())
                    }
                    if (!deleted) {
                        toastOnUi(R.string.fast_vits_model_delete_busy)
                        return@launch
                    }
                    val systemDefault = GSON.toJson(
                        SelectItem(getString(R.string.system_tts), "")
                    )
                    ReadBook.book?.takeIf {
                        it.getTtsEngine() == FastVitsOfflineTts.ENGINE_TOKEN
                    }?.let { book ->
                        book.setTtsEngine(null)
                        ReadBook.executor.execute { runCatching { book.update() } }
                    }
                    if (AppConfig.ttsEngine == FastVitsOfflineTts.ENGINE_TOKEN) {
                        AppConfig.ttsEngine = systemDefault
                    }
                    if (ttsEngine == FastVitsOfflineTts.ENGINE_TOKEN) {
                        upTts(systemDefault)
                    }
                    ReadAloud.upReadAloudClass()
                    fastVitsHeaderBinding?.let(::updateFastVitsHeader)
                    toastOnUi(R.string.fast_vits_model_deleted)
                }
            }
        }
    }

    private fun startOfflineModelInstall(force: Boolean = false) {
        KokoroOfflineTts.updateModelInstallSnapshot(
            KokoroOfflineTts.ModelInstallSnapshot(
                KokoroOfflineTts.ModelInstallPhase.PREPARING,
            )
        )
        KokoroModelInstallService.start(requireContext(), force)
        toastOnUi(R.string.kokoro_model_install_started)
        offlineHeaderBinding?.let(::updateOfflineHeader)
    }

    private fun updateOfflineHeader(header: ItemOfflineTtsEngineBinding) {
        val installed = KokoroOfflineTts.isInstalled(requireContext())
        val snapshot = KokoroOfflineTts.modelInstallSnapshot()
        val active = snapshot.phase in setOf(
            KokoroOfflineTts.ModelInstallPhase.PREPARING,
            KokoroOfflineTts.ModelInstallPhase.DOWNLOADING,
            KokoroOfflineTts.ModelInstallPhase.EXTRACTING,
        )
        header.pbModelProgress.isVisible = active
        header.llOfflineActions.isVisible = installed
        header.tvModelManage.isVisible = installed && !active
        when {
            active -> {
                header.tvAction.setText(R.string.kokoro_model_cancel_download)
                when (snapshot.phase) {
                    KokoroOfflineTts.ModelInstallPhase.DOWNLOADING -> {
                        header.pbModelProgress.isIndeterminate = snapshot.progress < 0
                        header.pbModelProgress.progress = snapshot.progress.coerceAtLeast(0)
                        header.tvSummary.text = if (snapshot.totalBytes > 0L) {
                            getString(
                                R.string.kokoro_model_downloading_detail,
                                snapshot.progress.coerceAtLeast(0),
                                Formatter.formatShortFileSize(requireContext(), snapshot.downloadedBytes),
                                Formatter.formatShortFileSize(requireContext(), snapshot.totalBytes),
                            )
                        } else {
                            getString(
                                R.string.kokoro_model_downloading_percent,
                                snapshot.progress.coerceAtLeast(0),
                            )
                        }
                    }
                    KokoroOfflineTts.ModelInstallPhase.EXTRACTING -> {
                        header.pbModelProgress.isIndeterminate = true
                        header.tvSummary.setText(R.string.kokoro_model_extracting)
                    }
                    else -> {
                        header.pbModelProgress.isIndeterminate = true
                        header.tvSummary.setText(R.string.kokoro_model_preparing)
                    }
                }
            }
            installed -> {
                header.pbModelProgress.isIndeterminate = false
                header.pbModelProgress.progress = 100
                header.tvSummary.text = getString(
                    R.string.kokoro_model_installed_summary_size,
                    Formatter.formatShortFileSize(
                        requireContext(),
                        KokoroOfflineTts.modelSizeBytes(requireContext()),
                    ),
                )
                header.tvAction.setText(R.string.kokoro_voice_settings)
            }
            snapshot.phase == KokoroOfflineTts.ModelInstallPhase.FAILED -> {
                header.tvSummary.setText(R.string.kokoro_model_failed_summary)
                header.tvAction.setText(R.string.kokoro_model_retry_download)
            }
            snapshot.phase == KokoroOfflineTts.ModelInstallPhase.CANCELED -> {
                header.tvSummary.setText(R.string.kokoro_model_canceled_summary)
                header.tvAction.setText(R.string.kokoro_model_retry_download)
            }
            else -> {
                header.tvSummary.setText(R.string.kokoro_model_not_installed)
                header.tvAction.setText(R.string.kokoro_model_download)
            }
        }
        header.cbName.isEnabled = installed
        if (!installed && ttsEngine == KokoroOfflineTts.ENGINE_TOKEN) {
            header.cbName.isChecked = false
        }
    }

    private fun offlineInstallActive(): Boolean =
        KokoroOfflineTts.modelInstallSnapshot().phase in setOf(
            KokoroOfflineTts.ModelInstallPhase.PREPARING,
            KokoroOfflineTts.ModelInstallPhase.DOWNLOADING,
            KokoroOfflineTts.ModelInstallPhase.EXTRACTING,
        )

    private fun showOfflineModelMenu(anchor: View) {
        val sizeText = Formatter.formatShortFileSize(
            requireContext(),
            KokoroOfflineTts.modelSizeBytes(requireContext()),
        )
        popupActionMenu(requireContext()) {
            item(getString(R.string.kokoro_model_reinstall), "reinstall")
            item(getString(R.string.kokoro_model_delete_with_size, sizeText), "delete")
        }.show(anchor) { action ->
            when (action) {
                "reinstall" -> startOfflineModelInstall(force = true)
                "delete" -> confirmDeleteOfflineModel(sizeText)
            }
        }
    }

    private fun confirmDeleteOfflineModel(sizeText: String) {
        alert(R.string.kokoro_model_manage) {
            setMessage(getString(R.string.kokoro_model_delete_confirm, sizeText))
            noButton()
            yesButton {
                lifecycleScope.launch {
                    if (ReadAloud.ttsEngine == KokoroOfflineTts.ENGINE_TOKEN &&
                        BaseReadAloudService.isRun
                    ) {
                        ReadAloud.stop(requireContext())
                        delay(300)
                    }
                    val deleted = withContext(IO) {
                        KokoroOfflineTts.deleteModel(requireContext())
                    }
                    if (!deleted) {
                        toastOnUi(R.string.kokoro_model_delete_busy)
                        return@launch
                    }
                    val systemDefault = GSON.toJson(
                        SelectItem(getString(R.string.system_tts), "")
                    )
                    ReadBook.book?.takeIf {
                        it.getTtsEngine() == KokoroOfflineTts.ENGINE_TOKEN
                    }?.let { book ->
                        book.setTtsEngine(null)
                        ReadBook.executor.execute { runCatching { book.update() } }
                    }
                    if (AppConfig.ttsEngine == KokoroOfflineTts.ENGINE_TOKEN) {
                        AppConfig.ttsEngine = systemDefault
                    }
                    if (ttsEngine == KokoroOfflineTts.ENGINE_TOKEN) {
                        upTts(systemDefault)
                    }
                    ReadAloud.upReadAloudClass()
                    offlineHeaderBinding?.let(::updateOfflineHeader)
                    toastOnUi(R.string.kokoro_model_deleted)
                }
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Boolean>(KokoroOfflineTts.MODEL_EVENT) {
            offlineHeaderBinding?.let(::updateOfflineHeader)
        }
        observeEvent<Boolean>(FastVitsOfflineTts.MODEL_EVENT) {
            fastVitsHeaderBinding?.let(::updateFastVitsHeader)
        }
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<HttpTTS, ItemHttpTtsBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemHttpTtsBinding {
            return ItemHttpTtsBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemHttpTtsBinding,
            item: HttpTTS,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                cbName.text = item.name
                val isChecked = item.id.toString() == ttsEngine
                if (isChecked) {
                    currentSelect = holder.layoutPosition - getHeaderCount()
                }
                cbName.isChecked = isChecked
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemHttpTtsBinding) {
            binding.run {
                cbName.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        val id = httpTTS.id.toString()
                        upTts(id)
                        if (httpTTS.shouldOpenLoginOnSelection()) {
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "httpTts")
                                putExtra("key", id)
                            }
                        }
                    }
                }
                cbName.setOnLongClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        if (httpTTS.hasLoginCapability()) {
                            val id = httpTTS.id.toString()
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "httpTts")
                                putExtra("key", id)
                            }
                            return@setOnLongClickListener true
                        }
                    }
                    false
                }
                ivEdit.setOnClickListener {
                    val id = getItemByLayoutPosition(holder.layoutPosition)!!.id
                    showDialogFragment(HttpTtsEditDialog(id))
                }
                ivMenuDelete.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        alert(R.string.draw) {
                            setMessage(getString(R.string.sure_del) + "\n" + httpTTS.name)
                            noButton()
                            yesButton {
                                httpTTS.clearSharedGlobalState()
                                appDb.httpTTSDao.delete(httpTTS)
                            }
                        }
                    }
                }
            }
        }

    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }

}
