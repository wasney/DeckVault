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
import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.model.SavedDeck
import com.deckvault.offline.optimizer.CompressionPreset
import com.deckvault.offline.optimizer.DeckOptimizer
import com.deckvault.offline.util.DebugLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed class OptimizeUiState {
    object Idle : OptimizeUiState()
    data class Running(
        val title: String,
        val detail: String,
        val progress: Int,
        val max: Int,
        val queuedCount: Int = 0
    ) : OptimizeUiState()
    data class Completed(val summary: String) : OptimizeUiState()
    data class Failed(val message: String) : OptimizeUiState()
}

object OptimizationStateHolder {
    private val _state = MutableStateFlow<OptimizeUiState>(OptimizeUiState.Idle)
    val state: StateFlow<OptimizeUiState> = _state

    private val _notice = MutableStateFlow<DownloadNotice?>(null)
    val notice: StateFlow<DownloadNotice?> = _notice

    fun update(s: OptimizeUiState) {
        _state.value = s
    }

    fun emitNotice(msg: String, isError: Boolean = false) {
        _notice.value = DownloadNotice(System.nanoTime(), msg, isError)
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun reset() {
        _state.value = OptimizeUiState.Idle
    }
}

class DeckOptimizationService : Service() {

    private data class OptimizeTask(
        val sourceDeckId: String,
        val preset: CompressionPreset,
        val createCopy: Boolean
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any()
    private val pendingQueue = ArrayDeque<OptimizeTask>()

    private var workerJob: Job? = null
    private var activeTaskJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.init(applicationContext)
        createChannel()

        when (intent?.action) {
            ACTION_CANCEL -> {
                synchronized(queueLock) { pendingQueue.clear() }
                activeTaskJob?.cancel()
                workerJob?.cancel()
                OptimizationStateHolder.update(OptimizeUiState.Idle)
                stopCleanly()
                return START_NOT_STICKY
            }

            ACTION_OPTIMIZE -> {
                val deckIds = intent.getStringArrayListExtra(EXTRA_DECK_IDS).orEmpty()
                val preset = CompressionPreset.fromId(intent.getStringExtra(EXTRA_PRESET_ID))
                val createCopy = intent.getBooleanExtra(EXTRA_CREATE_COPY, true)
                if (deckIds.isEmpty() || preset == CompressionPreset.ORIGINAL) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundCompat(buildNotification("Preparing compression...", 0, 1))

                synchronized(queueLock) {
                    deckIds.forEach { id ->
                        pendingQueue.addLast(OptimizeTask(id, preset, createCopy))
                    }
                    if (workerJob?.isActive != true) {
                        workerJob = serviceScope.launch { processQueueLoop() }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processQueueLoop() {
        val repo = DeckRepository(applicationContext)
        val optimizer = DeckOptimizer(applicationContext)

        try {
            while (serviceScope.isActive) {
                val task: OptimizeTask
                synchronized(queueLock) {
                    if (pendingQueue.isEmpty()) return
                    task = pendingQueue.removeFirst()
                }
                val job = serviceScope.launch {
                    runOptimizeTask(repo, optimizer, task)
                }
                activeTaskJob = job
                job.join()
                activeTaskJob = null
            }
        } finally {
            val remaining = synchronized(queueLock) { pendingQueue.isNotEmpty() }
            if (remaining && serviceScope.isActive) {
                workerJob = serviceScope.launch { processQueueLoop() }
            } else {
                if (OptimizationStateHolder.state.value is OptimizeUiState.Running) {
                    OptimizationStateHolder.update(OptimizeUiState.Idle)
                }
                stopCleanly()
            }
        }
    }

    private suspend fun runOptimizeTask(
        repo: DeckRepository,
        optimizer: DeckOptimizer,
        task: OptimizeTask
    ) {
        val sourceDeck = repo.getDeckById(task.sourceDeckId) ?: return
        val sourceDir = repo.getDeckDir(sourceDeck.id)
        if (!sourceDir.exists()) return

        var createdCopyDir: File? = null
        val targetDeckId = if (task.createCopy) UUID.randomUUID().toString().take(8) else sourceDeck.id
        val targetDir = repo.getDeckDir(targetDeckId)
        val displayName = if (task.createCopy) {
            val presetTag = task.preset.label.substringBefore(" (")
            "${sourceDeck.title} ($presetTag)"
        } else {
            sourceDeck.title
        }

        try {
            val qNow = synchronized(queueLock) { pendingQueue.size }
            if (task.createCopy) {
                createdCopyDir = targetDir
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()

                val allFiles = sourceDir.walkTopDown().filter { it.isFile }.toList()
                val totalCopy = allFiles.size.coerceAtLeast(1)
                allFiles.forEachIndexed { idx, file ->
                    coroutineContext.ensureActive()
                    val rel = file.relativeTo(sourceDir).path
                    val dest = File(targetDir, rel)
                    dest.parentFile?.mkdirs()
                    file.copyTo(dest, overwrite = true)
                    if (idx == 0 || idx % 30 == 0 || idx == allFiles.lastIndex) {
                        val detail = "Cloning safe copy (${idx + 1}/$totalCopy files)..."
                        OptimizationStateHolder.update(
                            OptimizeUiState.Running(displayName, detail, idx + 1, totalCopy, qNow)
                        )
                        updateNotification("$displayName: $detail", idx + 1, totalCopy)
                    }
                }
            }

            val result = optimizer.optimizeDirectory(targetDir, task.preset) { prog ->
                val qRem = synchronized(queueLock) { pendingQueue.size }
                val savedStr = DeckRepository.formatBytes(prog.bytesSavedSoFar)
                val detail = "${prog.phase} • Saved $savedStr"
                OptimizationStateHolder.update(
                    OptimizeUiState.Running(
                        title = "Optimizing $displayName",
                        detail = detail,
                        progress = prog.currentStep,
                        max = prog.totalSteps,
                        queuedCount = qRem
                    )
                )
                updateNotification("$displayName: $detail", prog.currentStep, prog.totalSteps)
            }

            val baselineOriginal = if (sourceDeck.originalBytes > 0L) sourceDeck.originalBytes else result.bytesBefore
            val updatedDeck = SavedDeck(
                id = targetDeckId,
                title = displayName,
                sourceUrl = sourceDeck.sourceUrl,
                baseDirUrl = sourceDeck.baseDirUrl,
                entryFile = sourceDeck.entryFile,
                fileCount = result.fileCount,
                totalBytes = result.bytesAfter,
                savedAtMillis = System.currentTimeMillis(),
                includeExternalMedia = sourceDeck.includeExternalMedia,
                optimizationPreset = task.preset.id,
                originalBytes = baselineOriginal
            )
            repo.upsertDeck(updatedDeck)

            val savedTotal = (result.bytesBefore - result.bytesAfter).coerceAtLeast(0L)
            val pct = if (result.bytesBefore > 0L) ((savedTotal * 100L) / result.bytesBefore).toInt() else 0
            val summary = "Optimized \"${updatedDeck.title}\": ${DeckRepository.formatBytes(result.bytesBefore)} -> ${DeckRepository.formatBytes(result.bytesAfter)} (Saved $pct%)"

            val hasMore = synchronized(queueLock) { pendingQueue.isNotEmpty() }
            if (hasMore) {
                OptimizationStateHolder.emitNotice(summary)
            } else {
                OptimizationStateHolder.update(OptimizeUiState.Completed(summary))
            }
        } catch (_: CancellationException) {
            createdCopyDir?.deleteRecursively()
            DebugLogger.log("Optimizer", "Optimization cancelled for '$displayName'")
        } catch (e: Exception) {
            createdCopyDir?.deleteRecursively()
            DebugLogger.error("Optimizer", "Optimization failed for '$displayName'", e)
            val hasMore = synchronized(queueLock) { pendingQueue.isNotEmpty() }
            val msg = "Optimization failed for \"$displayName\": ${e.message}"
            if (hasMore) {
                OptimizationStateHolder.emitNotice(msg, isError = true)
            } else {
                OptimizationStateHolder.update(OptimizeUiState.Failed(msg))
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun stopCleanly() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Offline Deck Optimization",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, current: Int, max: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DeckVault Storage Optimizer")
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
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "deckvault_optimizer"
        private const val NOTIF_ID = 4042

        const val ACTION_OPTIMIZE = "com.deckvault.offline.OPTIMIZE_DECKS"
        const val ACTION_CANCEL = "com.deckvault.offline.CANCEL_OPTIMIZE"
        const val EXTRA_DECK_IDS = "extra_deck_ids"
        const val EXTRA_PRESET_ID = "extra_preset_id"
        const val EXTRA_CREATE_COPY = "extra_create_copy"

        fun start(
            context: Context,
            deckIds: List<String>,
            preset: CompressionPreset,
            createCopy: Boolean
        ) {
            val intent = Intent(context, DeckOptimizationService::class.java).apply {
                action = ACTION_OPTIMIZE
                putStringArrayListExtra(EXTRA_DECK_IDS, ArrayList(deckIds))
                putExtra(EXTRA_PRESET_ID, preset.id)
                putExtra(EXTRA_CREATE_COPY, createCopy)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            runCatching {
                val intent = Intent(context, DeckOptimizationService::class.java).apply {
                    action = ACTION_CANCEL
                }
                context.startService(intent)
            }
        }
    }
}
