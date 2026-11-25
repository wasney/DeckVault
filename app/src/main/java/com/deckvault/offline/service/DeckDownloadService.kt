package com.deckvault.offline.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.deckvault.offline.R
import com.deckvault.offline.crawler.DeckCrawler
import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.optimizer.CompressionPreset
import com.deckvault.offline.optimizer.DeckOptimizer
import com.deckvault.offline.util.AppUpdatePrefs
import com.deckvault.offline.util.DebugLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

sealed class DownloadUiState {
    object Idle : DownloadUiState()
    data class Running(
        val title: String,
        val detail: String,
        val downloadedFiles: Int,
        val totalDiscovered: Int,
        val queuedCount: Int = 0
    ) : DownloadUiState()
    data class Completed(val title: String, val fileCount: Int, val sizeText: String) : DownloadUiState()
    data class Failed(val message: String) : DownloadUiState()
}

data class DownloadNotice(
    val id: Long,
    val message: String,
    val isError: Boolean = false
)

object DownloadStateHolder {
    private val _state = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val state: StateFlow<DownloadUiState> = _state

    // Emits whenever any individual queued item completes or fails so UI refreshes immediately
    private val _notice = MutableStateFlow<DownloadNotice?>(null)
    val notice: StateFlow<DownloadNotice?> = _notice

    fun update(newState: DownloadUiState) {
        _state.value = newState
    }

    fun emitNotice(message: String, isError: Boolean = false) {
        _notice.value = DownloadNotice(System.nanoTime(), message, isError)
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun reset() {
        _state.value = DownloadUiState.Idle
    }
}

class DeckDownloadService : Service() {

    private data class DownloadTask(
        val deckId: String,
        val url: String,
        val customTitle: String?,
        val includeExternal: Boolean,
        val isResync: Boolean
    ) {
        val displayTitle: String
            get() = customTitle?.takeIf { it.isNotBlank() } ?: deriveShortLabel(url)

        private fun deriveShortLabel(rawUrl: String): String {
            val cleaned = rawUrl.substringBefore('?').substringBefore('#').trimEnd('/')
            val parts = cleaned.split('/').filter { it.isNotBlank() && !it.equals("index.html", true) }
            val last = parts.lastOrNull() ?: "Web Deck"
            return last.replace("_", " ").replace("-", " ").take(40)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any()
    private val pendingQueue = ArrayDeque<DownloadTask>()

    private var workerJob: Job? = null
    private var currentTaskJob: Job? = null
    private var activeTask: DownloadTask? = null
    private var isForegroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.init(applicationContext)
        createChannel()

        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                val cleared: Int
                synchronized(queueLock) {
                    cleared = pendingQueue.size
                    pendingQueue.clear()
                }
                DebugLogger.log("Queue", "Cancel ALL requested (cleared $cleared queued item(s))")
                currentTaskJob?.cancel()
                workerJob?.cancel()
                DownloadStateHolder.update(DownloadUiState.Idle)
                stopServiceCleanly()
                return START_NOT_STICKY
            }

            ACTION_SKIP_CURRENT -> {
                DebugLogger.log("Queue", "Skip current download requested")
                currentTaskJob?.cancel()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)?.trim()
                if (url.isNullOrBlank()) {
                    if (!isForegroundStarted) stopSelf()
                    return START_NOT_STICKY
                }
                val title = intent.getStringExtra(EXTRA_TITLE)
                val includeExt = intent.getBooleanExtra(EXTRA_INCLUDE_EXTERNAL, true)
                val existingId = intent.getStringExtra(EXTRA_DECK_ID)

                // Always satisfy Android's startForegroundService() contract immediately
                val runningNow = synchronized(queueLock) { activeTask != null }
                val initialNotifText = if (runningNow) {
                    "Added to queue..."
                } else {
                    "Starting download..."
                }
                startForegroundCompat(buildNotification(initialNotifText, 0, 0))

                enqueueTask(
                    DownloadTask(
                        deckId = existingId ?: UUID.randomUUID().toString().take(8),
                        url = url,
                        customTitle = title,
                        includeExternal = includeExt,
                        isResync = existingId != null
                    )
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun enqueueTask(task: DownloadTask) {
        val queuedPosition: Int
        val currentStateSnapshot: DownloadUiState
        synchronized(queueLock) {
            val alreadyActive = activeTask?.url.equals(task.url, ignoreCase = true)
            val alreadyQueued = pendingQueue.any { it.url.equals(task.url, ignoreCase = true) }
            if (alreadyActive || alreadyQueued) {
                DebugLogger.log("Queue", "Ignored duplicate URL already in queue: ${task.url}")
                DownloadStateHolder.emitNotice("\"${task.displayTitle}\" is already downloading or queued.")
                return
            }

            pendingQueue.addLast(task)
            queuedPosition = pendingQueue.size
            currentStateSnapshot = DownloadStateHolder.state.value

            if (workerJob?.isActive != true) {
                workerJob = serviceScope.launch { processQueueLoop() }
            }
        }

        if (currentStateSnapshot is DownloadUiState.Running) {
            // Immediately reflect the updated queue badge while current deck keeps running
            val qSize = synchronized(queueLock) { pendingQueue.size }
            DownloadStateHolder.update(currentStateSnapshot.copy(queuedCount = qSize))
            DownloadStateHolder.emitNotice("Queued \"${task.displayTitle}\" ($qSize waiting)")
            DebugLogger.log("Queue", "Enqueued '${task.displayTitle}' (position #$queuedPosition waiting)")
        } else {
            DebugLogger.log("Queue", "Started '${task.displayTitle}'")
        }
    }

    private suspend fun processQueueLoop() {
        val repo = DeckRepository(applicationContext)
        val crawler = DeckCrawler()

        try {
            while (serviceScope.isActive) {
                val nextTask: DownloadTask
                val remainingAfterPop: Int
                synchronized(queueLock) {
                    if (pendingQueue.isEmpty()) {
                        activeTask = null
                        return
                    }
                    nextTask = pendingQueue.removeFirst()
                    activeTask = nextTask
                    remainingAfterPop = pendingQueue.size
                }

                runSingleTask(repo, crawler, nextTask, remainingAfterPop)
            }
        } finally {
            synchronized(queueLock) {
                activeTask = null
            }
            val stillHasWork = synchronized(queueLock) { pendingQueue.isNotEmpty() }
            if (stillHasWork && serviceScope.isActive) {
                workerJob = serviceScope.launch { processQueueLoop() }
            } else {
                val cur = DownloadStateHolder.state.value
                if (cur is DownloadUiState.Running) {
                    DownloadStateHolder.update(DownloadUiState.Idle)
                }
                stopServiceCleanly()
            }
        }
    }

    private suspend fun runSingleTask(
        repo: DeckRepository,
        crawler: DeckCrawler,
        task: DownloadTask,
        initialQueued: Int
    ) {
        val job = serviceScope.launch {
            try {
                val qCount0 = synchronized(queueLock) { pendingQueue.size }
                DownloadStateHolder.update(
                    DownloadUiState.Running(
                        title = task.displayTitle,
                        detail = "Connecting to server...",
                        downloadedFiles = 0,
                        totalDiscovered = 1,
                        queuedCount = qCount0
                    )
                )
                updateNotification(
                    buildQueueNotifText(task.displayTitle, "Connecting...", qCount0),
                    0,
                    1
                )

                val saved = crawler.crawlDeck(
                    deckId = task.deckId,
                    sourceUrlString = task.url,
                    customTitle = task.customTitle,
                    includeExternalMedia = task.includeExternal,
                    outputDir = repo.getDeckDir(task.deckId)
                ) { progress ->
                    val qNow = synchronized(queueLock) { pendingQueue.size }
                    val sizeStr = DeckRepository.formatBytes(progress.downloadedBytes)
                    val detail = "${progress.phase} • $sizeStr"
                    DownloadStateHolder.update(
                        DownloadUiState.Running(
                            title = task.displayTitle,
                            detail = detail,
                            downloadedFiles = progress.downloadedFiles,
                            totalDiscovered = progress.totalDiscovered.coerceAtLeast(1),
                            queuedCount = qNow
                        )
                    )
                    updateNotification(
                        buildQueueNotifText(task.displayTitle, detail, qNow),
                        progress.downloadedFiles,
                        progress.totalDiscovered
                    )
                }

                var finalDeck = saved
                val defaultPreset = CompressionPreset.fromId(
                    AppUpdatePrefs.getDefaultCompressionPresetId(applicationContext)
                )
                if (defaultPreset != CompressionPreset.ORIGINAL) {
                    val optimizer = DeckOptimizer(applicationContext)
                    val optResult = optimizer.optimizeDirectory(
                        repo.getDeckDir(task.deckId),
                        defaultPreset
                    ) { prog ->
                        val qNow = synchronized(queueLock) { pendingQueue.size }
                        val savedStr = DeckRepository.formatBytes(prog.bytesSavedSoFar)
                        val detail = "${prog.phase} • Saved $savedStr"
                        DownloadStateHolder.update(
                            DownloadUiState.Running(
                                title = task.displayTitle,
                                detail = detail,
                                downloadedFiles = prog.currentStep,
                                totalDiscovered = prog.totalSteps.coerceAtLeast(1),
                                queuedCount = qNow
                            )
                        )
                        updateNotification(
                            buildQueueNotifText(task.displayTitle, detail, qNow),
                            prog.currentStep,
                            prog.totalSteps
                        )
                    }
                    finalDeck = saved.copy(
                        fileCount = optResult.fileCount,
                        totalBytes = optResult.bytesAfter,
                        optimizationPreset = defaultPreset.id,
                        originalBytes = optResult.bytesBefore
                    )
                }

                repo.upsertDeck(finalDeck)
                val sizeText = DeckRepository.formatBytes(finalDeck.totalBytes)
                val moreQueued = synchronized(queueLock) { pendingQueue.isNotEmpty() }
                if (moreQueued) {
                    DownloadStateHolder.emitNotice(
                        "Saved \"${finalDeck.title}\" (${finalDeck.fileCount} files, $sizeText) — starting next..."
                    )
                } else {
                    DownloadStateHolder.update(
                        DownloadUiState.Completed(
                            title = finalDeck.title,
                            fileCount = finalDeck.fileCount,
                            sizeText = sizeText
                        )
                    )
                }
            } catch (_: CancellationException) {
                DebugLogger.log("Queue", "Cancelled task '${task.displayTitle}' (id=${task.deckId})")
                if (!task.isResync) {
                    repo.getDeckDir(task.deckId).deleteRecursively()
                }
                val moreQueued = synchronized(queueLock) { pendingQueue.isNotEmpty() }
                if (moreQueued) {
                    DownloadStateHolder.emitNotice("Skipped \"${task.displayTitle}\" — starting next...")
                }
            } catch (e: Exception) {
                DebugLogger.error("Service", "Crawl failed for '${task.displayTitle}'", e)
                if (!task.isResync) {
                    repo.getDeckDir(task.deckId).deleteRecursively()
                }
                val moreQueued = synchronized(queueLock) { pendingQueue.isNotEmpty() }
                val errMsg = "Failed \"${task.displayTitle}\": ${e.message ?: "Unknown error"}"
                if (moreQueued) {
                    DownloadStateHolder.emitNotice(errMsg, isError = true)
                } else {
                    DownloadStateHolder.update(DownloadUiState.Failed(errMsg))
                }
            }
        }

        currentTaskJob = job
        job.join()
        currentTaskJob = null
    }

    private fun buildQueueNotifText(title: String, detail: String, queuedCount: Int): String {
        val queueSuffix = if (queuedCount > 0) " (+$queuedCount queued)" else ""
        return "$title: $detail$queueSuffix"
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        isForegroundStarted = true
    }

    private fun stopServiceCleanly() {
        isForegroundStarted = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Offline Deck Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, current: Int, max: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DeckVault Offline Capture")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max.coerceAtLeast(1), current.coerceAtMost(max.coerceAtLeast(1)), max <= 1)
            .build()
    }

    private fun updateNotification(text: String, current: Int, max: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text, current, max))
    }

    override fun onDestroy() {
        isForegroundStarted = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "deckvault_downloads"
        private const val NOTIF_ID = 4041

        const val ACTION_START = "com.deckvault.offline.START_DOWNLOAD"
        const val ACTION_CANCEL_ALL = "com.deckvault.offline.CANCEL_ALL"
        const val ACTION_SKIP_CURRENT = "com.deckvault.offline.SKIP_CURRENT"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_INCLUDE_EXTERNAL = "extra_include_external"
        const val EXTRA_DECK_ID = "extra_deck_id"

        fun start(
            context: Context,
            url: String,
            title: String?,
            includeExternal: Boolean,
            existingDeckId: String? = null
        ) {
            val intent = Intent(context, DeckDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_INCLUDE_EXTERNAL, includeExternal)
                if (existingDeckId != null) putExtra(EXTRA_DECK_ID, existingDeckId)
            }
            context.startForegroundService(intent)
        }

        fun cancelAll(context: Context) {
            runCatching {
                val intent = Intent(context, DeckDownloadService::class.java).apply {
                    action = ACTION_CANCEL_ALL
                }
                context.startService(intent)
            }
        }

        fun skipCurrent(context: Context) {
            runCatching {
                val intent = Intent(context, DeckDownloadService::class.java).apply {
                    action = ACTION_SKIP_CURRENT
                }
                context.startService(intent)
            }
        }
    }
}
