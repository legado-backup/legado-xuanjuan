package io.legado.app.service

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.tts.FastVitsOfflineTts
import io.legado.app.utils.compress.LibArchiveUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import splitties.systemservices.notificationManager

class FastVitsModelInstallService : BaseService() {

    companion object {
        private const val EXTRA_FORCE_INSTALL = "forceInstall"

        fun start(context: Context, force: Boolean = false) {
            context.startForegroundServiceCompat(
                Intent(context, FastVitsModelInstallService::class.java).apply {
                    action = IntentAction.start
                    putExtra(EXTRA_FORCE_INSTALL, force)
                }
            )
        }

        fun stop(context: Context) {
            context.startForegroundServiceCompat(
                Intent(context, FastVitsModelInstallService::class.java).apply {
                    action = IntentAction.stop
                }
            )
        }
    }

    private var installJob: Job? = null
    @Volatile
    private var notificationText = "准备极速离线模型…"
    @Volatile
    private var notificationProgress = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (result == START_NOT_STICKY) return result
        when (intent?.action) {
            IntentAction.start -> startInstall(
                startId,
                intent.getBooleanExtra(EXTRA_FORCE_INSTALL, false),
            )
            IntentAction.stop -> {
                installJob?.cancel()
                installJob = null
                stopSelfResult(startId)
            }
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        installJob?.cancel()
        installJob = null
        notificationManager.cancel(NotificationId.FastVitsModelInstallService)
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        startForeground(NotificationId.FastVitsModelInstallService, buildNotification().build())
    }

    private fun startInstall(startId: Int, force: Boolean) {
        if (installJob?.isActive == true) {
            toastOnUi(R.string.fast_vits_model_install_running)
            return
        }
        if (FastVitsOfflineTts.isInstalled(this) && !force) {
            publishInstallState(
                FastVitsOfflineTts.ModelInstallSnapshot(
                    FastVitsOfflineTts.ModelInstallPhase.READY,
                    progress = 100,
                )
            )
            toastOnUi(R.string.fast_vits_model_installed)
            stopSelfResult(startId)
            return
        }
        installJob = lifecycleScope.launch(IO) {
            val archive = File(cacheDir, "${FastVitsOfflineTts.MODEL_DIR_NAME}.tar.bz2.part")
            val root = FastVitsOfflineTts.modelRoot(this@FastVitsModelInstallService)
            val staging = File(root, ".install-${UUID.randomUUID()}")
            try {
                publishInstallState(
                    FastVitsOfflineTts.ModelInstallSnapshot(
                        FastVitsOfflineTts.ModelInstallPhase.PREPARING,
                    )
                )
                root.mkdirs()
                archive.delete()
                staging.deleteRecursively()
                updateNotification(getString(R.string.fast_vits_model_downloading), 0)
                downloadArchive(archive)
                currentCoroutineContext().ensureActive()
                publishInstallState(
                    FastVitsOfflineTts.ModelInstallSnapshot(
                        FastVitsOfflineTts.ModelInstallPhase.EXTRACTING,
                    )
                )
                updateNotification(getString(R.string.fast_vits_model_extracting), -1)
                staging.mkdirs()
                ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    LibArchiveUtils.unArchive(pfd, staging)
                }
                currentCoroutineContext().ensureActive()
                val candidate = File(staging, FastVitsOfflineTts.MODEL_DIR_NAME)
                    .takeIf { it.isDirectory }
                    ?: staging
                val missing = FastVitsOfflineTts.missingModelFiles(candidate)
                check(missing.isEmpty()) {
                    "极速离线模型文件不完整: ${missing.joinToString()}"
                }
                installAtomically(candidate, root)
                updateNotification(getString(R.string.fast_vits_model_install_complete), 100)
                publishInstallState(
                    FastVitsOfflineTts.ModelInstallSnapshot(
                        FastVitsOfflineTts.ModelInstallPhase.READY,
                        progress = 100,
                    )
                )
                toastOnUi(R.string.fast_vits_model_install_complete)
            } catch (error: CancellationException) {
                publishInstallState(
                    FastVitsOfflineTts.ModelInstallSnapshot(
                        FastVitsOfflineTts.ModelInstallPhase.CANCELED,
                    )
                )
                throw error
            } catch (error: Throwable) {
                AppLog.put("安装 Fast VITS 极速离线模型失败\n${error.localizedMessage}", error, true)
                updateNotification(getString(R.string.fast_vits_model_install_failed), -1)
                toastOnUi(
                    "${getString(R.string.fast_vits_model_install_failed)}: " +
                        (error.localizedMessage ?: "未知错误")
                )
                publishInstallState(
                    FastVitsOfflineTts.ModelInstallSnapshot(
                        FastVitsOfflineTts.ModelInstallPhase.FAILED,
                    )
                )
            } finally {
                archive.delete()
                staging.deleteRecursively()
                installJob = null
                stopSelfResult(startId)
            }
        }
    }

    private suspend fun downloadArchive(target: File) {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
        client.newCallResponse(retry = 2) {
            url(FastVitsOfflineTts.MODEL_DOWNLOAD_URL)
            get()
        }.use { response ->
            check(response.isSuccessful) { "下载失败 HTTP ${response.code}" }
            val body = response.body
            val total = body.contentLength()
            var copied = 0L
            var lastPercent = -1
            publishInstallState(
                FastVitsOfflineTts.ModelInstallSnapshot(
                    phase = FastVitsOfflineTts.ModelInstallPhase.DOWNLOADING,
                    progress = 0,
                    downloadedBytes = 0L,
                    totalBytes = total.coerceAtLeast(0L),
                )
            )
            body.byteStream().buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0) {
                            val percent = ((copied * 100L) / total).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                publishInstallState(
                                    FastVitsOfflineTts.ModelInstallSnapshot(
                                        phase = FastVitsOfflineTts.ModelInstallPhase.DOWNLOADING,
                                        progress = percent,
                                        downloadedBytes = copied,
                                        totalBytes = total.coerceAtLeast(0L),
                                    )
                                )
                                updateNotification(
                                    getString(R.string.fast_vits_model_downloading_percent, percent),
                                    percent,
                                )
                            }
                        }
                    }
                }
            }
            check(target.length() > 0L) { "下载文件为空" }
        }
    }

    private fun publishInstallState(snapshot: FastVitsOfflineTts.ModelInstallSnapshot) {
        FastVitsOfflineTts.updateModelInstallSnapshot(snapshot)
        postEvent(
            FastVitsOfflineTts.MODEL_EVENT,
            snapshot.phase == FastVitsOfflineTts.ModelInstallPhase.READY,
        )
    }

    private fun installAtomically(candidate: File, root: File) {
        val target = File(root, FastVitsOfflineTts.MODEL_DIR_NAME)
        val backup = File(root, ".backup-${UUID.randomUUID()}")
        var backedUp = false
        try {
            if (target.exists()) {
                check(target.renameTo(backup)) { "无法备份已有模型" }
                backedUp = true
            }
            check(candidate.renameTo(target)) { "无法启用新模型" }
            check(FastVitsOfflineTts.missingModelFiles(target).isEmpty()) {
                "安装后的极速离线模型校验失败"
            }
            if (backedUp) backup.deleteRecursively()
        } catch (error: Throwable) {
            if (!target.exists() && backedUp && backup.exists()) {
                backup.renameTo(target)
            }
            throw error
        } finally {
            if (target.exists() && backup.exists()) backup.deleteRecursively()
        }
    }

    private fun updateNotification(text: String, progress: Int) {
        notificationText = text
        notificationProgress = progress
        notificationManager.notify(
            NotificationId.FastVitsModelInstallService,
            buildNotification().build(),
        )
    }

    private fun buildNotification(): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.fast_vits_offline_engine))
            .setContentText(notificationText)
            .setOnlyAlertOnce(true)
            .setOngoing(notificationProgress < 100)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(
                100,
                notificationProgress.coerceAtLeast(0),
                notificationProgress < 0,
            )
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<FastVitsModelInstallService>(IntentAction.stop),
            )
    }
}
