package com.deckvault.offline.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.deckvault.offline.R
import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.databinding.ActivityMainBinding
import com.deckvault.offline.databinding.DialogAddDeckBinding
import com.deckvault.offline.databinding.DialogOptimizeDeckBinding
import com.deckvault.offline.model.SavedDeck
import com.deckvault.offline.optimizer.CompressionPreset
import com.deckvault.offline.optimizer.DeckOptimizer
import com.deckvault.offline.service.DeckDownloadService
import com.deckvault.offline.service.DeckOptimizationService
import com.deckvault.offline.service.DownloadStateHolder
import com.deckvault.offline.service.DownloadUiState
import com.deckvault.offline.service.OptimizationStateHolder
import com.deckvault.offline.service.OptimizeUiState
import com.deckvault.offline.util.AppUpdateManager
import com.deckvault.offline.util.AppUpdatePrefs
import com.deckvault.offline.util.DebugLogger
import com.deckvault.offline.util.DeckShareHelper
import com.deckvault.offline.util.GitHubReleaseInfo
import com.deckvault.offline.util.ShortcutHelper
import com.deckvault.offline.util.UpdateCheckResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: DeckRepository
    private lateinit var adapter: DeckAdapter
    private var currentQueuedCount: Int = 0
    private var isOptimizingNow: Boolean = false
    private var pendingUpdateApk: File? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Service runs either way */ }

    private val importZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runImportDeck(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemBarInsets()

        repo = DeckRepository(this)
        adapter = DeckAdapter(
            onOpen = { openDeck(it) },
            onPin = { ShortcutHelper.pinDeckShortcut(this, it) },
            onMore = { deck, anchor -> showDeckOptionsMenu(deck, anchor) }
        )

        binding.rvDecks.layoutManager = LinearLayoutManager(this)
        binding.rvDecks.adapter = adapter

        binding.fabAddDeck.setOnClickListener { showAddDeckDialog(prefillUrl = null) }
        binding.btnCancelDownload.setOnClickListener { handleCancelClick() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnImport.setOnClickListener {
            importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }

        requestNotificationPermissionIfNeeded()
        observeBackgroundStates()
        refreshList()
        handleIncomingShare(intent)
        maybeCheckUpdatesOnLaunch()
    }

    private fun maybeCheckUpdatesOnLaunch() {
        if (hasCheckedUpdateThisSession) return
        if (!AppUpdatePrefs.isCheckOnLaunchEnabled(this)) return
        hasCheckedUpdateThisSession = true

        val includePre = AppUpdatePrefs.isIncludePreReleases(this)
        lifecycleScope.launch {
            val result = AppUpdateManager.checkForUpdate(includePre)
            if (result is UpdateCheckResult.UpdateAvailable && !isFinishing && !isDestroyed) {
                showLaunchUpdateDialog(result.release)
            }
        }
    }

    private fun showLaunchUpdateDialog(release: GitHubReleaseInfo) {
        val sizeStr = if (release.apkExpectedSize > 0L) {
            " (${DeckRepository.formatBytes(release.apkExpectedSize)})"
        } else {
            ""
        }
        val betaBadge = if (release.isPreRelease) " [Beta]" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("DeckVault Update ${release.tagName}$betaBadge")
            .setMessage("A newer version$sizeStr is available on GitHub.\n\n${release.notes}")
            .setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstallUpdate(release)
            }
            .setNeutralButton("Settings") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstallUpdate(release: GitHubReleaseInfo) {
        binding.cardProgress.visibility = View.VISIBLE
        binding.btnCancelDownload.visibility = View.GONE
        binding.tvProgressTitle.text = "Updating to ${release.tagName}..."
        binding.tvProgressDetail.text = "Downloading ${release.apkName}..."
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = 100
        binding.progressBar.progress = 0

        lifecycleScope.launch {
            try {
                val apkFile = AppUpdateManager.downloadAndVerifyApk(
                    context = this@MainActivity,
                    release = release
                ) { downloaded, total ->
                    runOnUiThread {
                        if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.progress = pct
                            binding.tvProgressDetail.text =
                                "$pct% • ${DeckRepository.formatBytes(downloaded)} / ${DeckRepository.formatBytes(total)}"
                        } else {
                            binding.progressBar.isIndeterminate = true
                            binding.tvProgressDetail.text = "Downloaded ${DeckRepository.formatBytes(downloaded)}..."
                        }
                    }
                }
                binding.cardProgress.visibility = View.GONE
                if (!packageManager.canRequestPackageInstalls()) {
                    pendingUpdateApk = apkFile
                }
                AppUpdateManager.launchApkInstaller(this@MainActivity, apkFile)
            } catch (e: Exception) {
                binding.cardProgress.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleCancelClick() {
        if (isOptimizingNow) {
            DeckOptimizationService.cancel(this)
            return
        }
        if (currentQueuedCount <= 0) {
            DeckDownloadService.cancelAll(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Download Queue ($currentQueuedCount waiting)")
            .setMessage("Do you want to skip only the current download, or cancel the entire queue?")
            .setPositiveButton("Skip Current") { _, _ ->
                DeckDownloadService.skipCurrent(this)
            }
            .setNegativeButton("Cancel All") { _, _ ->
                DeckDownloadService.cancelAll(this)
            }
            .setNeutralButton("Keep Going", null)
            .show()
    }

    private fun applySystemBarInsets() {
        val baseTop = (12 * resources.displayMetrics.density).toInt()
        val fabBaseMargin = (20 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.contentContainer.setPadding(
                bars.left,
                bars.top + baseTop,
                bars.right,
                bars.bottom
            )
            val lp = binding.fabAddDeck.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.bottomMargin = fabBaseMargin + bars.bottom
                lp.marginEnd = fabBaseMargin + bars.right
                binding.fabAddDeck.layoutParams = lp
            }
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        val pending = pendingUpdateApk
        if (pending != null && pending.exists() && packageManager.canRequestPackageInstalls()) {
            pendingUpdateApk = null
            AppUpdateManager.launchApkInstaller(this, pending)
        }
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val urlRegex = Regex("""https?://\S+""")
            val extractedUrl = urlRegex.find(sharedText)?.value ?: sharedText.trim()
            showAddDeckDialog(prefillUrl = extractedUrl)
            intent.action = ""
        }
    }

    private fun observeBackgroundStates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DownloadStateHolder.state.collect { state ->
                        when (state) {
                            is DownloadUiState.Idle -> {
                                currentQueuedCount = 0
                                if (!isOptimizingNow) binding.cardProgress.visibility = View.GONE
                            }

                            is DownloadUiState.Running -> {
                                isOptimizingNow = false
                                currentQueuedCount = state.queuedCount
                                binding.cardProgress.visibility = View.VISIBLE
                                binding.btnCancelDownload.visibility = View.VISIBLE
                                binding.tvProgressTitle.text = if (state.queuedCount > 0) {
                                    "${state.title} (+${state.queuedCount} queued)"
                                } else {
                                    state.title
                                }
                                binding.tvProgressDetail.text = state.detail
                                if (state.totalDiscovered <= 1) {
                                    binding.progressBar.isIndeterminate = true
                                } else {
                                    binding.progressBar.isIndeterminate = false
                                    binding.progressBar.max = state.totalDiscovered
                                    binding.progressBar.progress = state.downloadedFiles
                                }
                            }

                            is DownloadUiState.Completed -> {
                                currentQueuedCount = 0
                                binding.cardProgress.visibility = View.GONE
                                refreshList()
                                Toast.makeText(
                                    this@MainActivity,
                                    "Saved \"${state.title}\" (${state.fileCount} files, ${state.sizeText})",
                                    Toast.LENGTH_LONG
                                ).show()
                                DownloadStateHolder.reset()
                            }

                            is DownloadUiState.Failed -> {
                                currentQueuedCount = 0
                                binding.cardProgress.visibility = View.GONE
                                Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                                DownloadStateHolder.reset()
                            }
                        }
                    }
                }

                launch {
                    DownloadStateHolder.notice.collect { notice ->
                        if (notice != null) {
                            refreshList()
                            Toast.makeText(this@MainActivity, notice.message, Toast.LENGTH_SHORT).show()
                            DownloadStateHolder.clearNotice()
                        }
                    }
                }

                launch {
                    OptimizationStateHolder.state.collect { state ->
                        when (state) {
                            is OptimizeUiState.Idle -> {
                                isOptimizingNow = false
                                if (DownloadStateHolder.state.value is DownloadUiState.Idle) {
                                    binding.cardProgress.visibility = View.GONE
                                }
                            }

                            is OptimizeUiState.Running -> {
                                isOptimizingNow = true
                                binding.cardProgress.visibility = View.VISIBLE
                                binding.btnCancelDownload.visibility = View.VISIBLE
                                binding.tvProgressTitle.text = if (state.queuedCount > 0) {
                                    "${state.title} (+${state.queuedCount} queued)"
                                } else {
                                    state.title
                                }
                                binding.tvProgressDetail.text = state.detail
                                if (state.max <= 1) {
                                    binding.progressBar.isIndeterminate = true
                                } else {
                                    binding.progressBar.isIndeterminate = false
                                    binding.progressBar.max = state.max
                                    binding.progressBar.progress = state.progress
                                }
                            }

                            is OptimizeUiState.Completed -> {
                                isOptimizingNow = false
                                binding.cardProgress.visibility = View.GONE
                                refreshList()
                                Toast.makeText(this@MainActivity, state.summary, Toast.LENGTH_LONG).show()
                                OptimizationStateHolder.reset()
                            }

                            is OptimizeUiState.Failed -> {
                                isOptimizingNow = false
                                binding.cardProgress.visibility = View.GONE
                                Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                                OptimizationStateHolder.reset()
                            }
                        }
                    }
                }

                launch {
                    OptimizationStateHolder.notice.collect { notice ->
                        if (notice != null) {
                            refreshList()
                            Toast.makeText(this@MainActivity, notice.message, Toast.LENGTH_SHORT).show()
                            OptimizationStateHolder.clearNotice()
                        }
                    }
                }
            }
        }
    }

    private fun refreshList() {
        val decks = repo.getAllDecks()
        adapter.submitList(decks)
        binding.layoutEmpty.visibility = if (decks.isEmpty()) View.VISIBLE else View.GONE
        val totalBytes = decks.sumOf { it.totalBytes }
        binding.tvStorageSummary.text = if (decks.isEmpty()) {
            getString(R.string.subtitle_tagline)
        } else {
            "${decks.size} offline deck(s) • ${DeckRepository.formatBytes(totalBytes)} total"
        }
    }

    private fun showAddDeckDialog(prefillUrl: String?) {
        val dialogBinding = DialogAddDeckBinding.inflate(LayoutInflater.from(this))
        if (!prefillUrl.isNullOrBlank()) {
            dialogBinding.etUrl.setText(prefillUrl)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save_offline) { _, _ ->
                val url = dialogBinding.etUrl.text?.toString()?.trim().orEmpty()
                val name = dialogBinding.etName.text?.toString()?.trim()
                val includeExt = dialogBinding.switchExternalMedia.isChecked

                if (url.isBlank()) {
                    Toast.makeText(this, "Please enter a valid URL.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                DeckDownloadService.start(this, url, name, includeExt)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showDeckOptionsMenu(deck: SavedDeck, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.btn_storage_info))
        popup.menu.add(0, 2, 1, getString(R.string.btn_optimize))
        popup.menu.add(0, 3, 2, getString(R.string.btn_share))
        popup.menu.add(0, 4, 3, getString(R.string.btn_rename))
        popup.menu.add(0, 5, 4, getString(R.string.btn_update))
        popup.menu.add(0, 6, 5, getString(R.string.btn_delete))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showStorageBreakdown(deck)
                2 -> showOptimizeDialog(deck)
                3 -> shareDeckViaQuickShare(deck)
                4 -> showRenameDialog(deck)
                5 -> confirmResync(deck)
                6 -> confirmDelete(deck)
            }
            true
        }
        popup.show()
    }

    private fun showStorageBreakdown(deck: SavedDeck) {
        Toast.makeText(this, "Scanning \"${deck.title}\"...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                DeckOptimizer.analyzeDeckStorage(repo.getDeckDir(deck.id))
            }
            if (isFinishing || isDestroyed) return@launch

            fun pct(part: Long): Int =
                if (stats.totalBytes > 0L) ((part * 100L) / stats.totalBytes).toInt() else 0

            val preset = CompressionPreset.fromId(deck.optimizationPreset)
            val msg = buildString {
                append("Total: ${DeckRepository.formatBytes(stats.totalBytes)} (${stats.totalFiles} files)\n")
                append("Status: ${preset.label}\n")
                if (deck.originalBytes > stats.totalBytes && deck.originalBytes > 0L) {
                    val saved = deck.originalBytes - stats.totalBytes
                    val savedPct = ((saved * 100L) / deck.originalBytes).toInt()
                    append("Original size: ${DeckRepository.formatBytes(deck.originalBytes)} (Saved $savedPct%)\n")
                }
                append("\n")
                append("• Videos: ${DeckRepository.formatBytes(stats.videos.bytes)} (${stats.videos.fileCount} files, ${pct(stats.videos.bytes)}%)\n")
                append("• Images: ${DeckRepository.formatBytes(stats.images.bytes)} (${stats.images.fileCount} files, ${pct(stats.images.bytes)}%)\n")
                append("• PDFs: ${DeckRepository.formatBytes(stats.pdfs.bytes)} (${stats.pdfs.fileCount} files, ${pct(stats.pdfs.bytes)}%)\n")
                append("• Code/SVG: ${DeckRepository.formatBytes(stats.textCode.bytes)} (${stats.textCode.fileCount} files, ${pct(stats.textCode.bytes)}%)\n")
                append("• Other: ${DeckRepository.formatBytes(stats.other.bytes)} (${stats.other.fileCount} files, ${pct(stats.other.bytes)}%)\n")
                if (stats.webpOptimizedCount > 0 || stats.gzipOptimizedCount > 0) {
                    append("\nOptimized files: ${stats.webpOptimizedCount} WebP images, ${stats.gzipOptimizedCount} GZIP code/SVG")
                }
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Storage Breakdown")
                .setMessage(msg)
                .setPositiveButton("Optimize / Compress…") { _, _ ->
                    showOptimizeDialog(deck)
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun showOptimizeDialog(deck: SavedDeck) {
        val dlgBinding = DialogOptimizeDeckBinding.inflate(LayoutInflater.from(this))
        dlgBinding.tvOptimizeSummary.text =
            "\"${deck.title}\" is currently ${DeckRepository.formatBytes(deck.totalBytes)} (${deck.fileCount} files)."

        MaterialAlertDialogBuilder(this)
            .setTitle("Optimize Offline Deck")
            .setView(dlgBinding.root)
            .setPositiveButton("Start Optimization") { _, _ ->
                val preset = when (dlgBinding.rgPreset.checkedRadioButtonId) {
                    R.id.rb_compact -> CompressionPreset.COMPACT
                    R.id.rb_lossless -> CompressionPreset.LOSSLESS
                    else -> CompressionPreset.BALANCED
                }
                val keepCopy = dlgBinding.switchKeepCopy.isChecked
                DeckOptimizationService.start(
                    context = this,
                    deckIds = listOf(deck.id),
                    preset = preset,
                    createCopy = keepCopy
                )
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun shareDeckViaQuickShare(deck: SavedDeck) {
        binding.cardProgress.visibility = View.VISIBLE
        binding.btnCancelDownload.visibility = View.GONE
        binding.tvProgressTitle.text = "Packaging \"${deck.title}\"..."
        binding.tvProgressDetail.text = "Preparing bundle for Quick Share..."
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = deck.fileCount.coerceAtLeast(1)
        binding.progressBar.progress = 0

        lifecycleScope.launch {
            try {
                val zipFile = withContext(Dispatchers.IO) {
                    DeckShareHelper.exportDeckToZip(this@MainActivity, repo, deck) { done, total ->
                        runOnUiThread {
                            binding.progressBar.max = total
                            binding.progressBar.progress = done
                            binding.tvProgressDetail.text = "Packing $done / $total files..."
                        }
                    }
                }
                binding.cardProgress.visibility = View.GONE

                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "com.deckvault.offline.fileprovider",
                    zipFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, deck.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share \"${deck.title}\" via Quick Share"))
            } catch (e: Exception) {
                binding.cardProgress.visibility = View.GONE
                DebugLogger.error("Share", "Failed to package deck", e)
                Toast.makeText(this@MainActivity, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runImportDeck(uri: Uri) {
        binding.cardProgress.visibility = View.VISIBLE
        binding.btnCancelDownload.visibility = View.GONE
        binding.tvProgressTitle.text = "Importing Offline Deck..."
        binding.tvProgressDetail.text = "Extracting files..."
        binding.progressBar.isIndeterminate = true

        lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    DeckShareHelper.importDeckFromUri(this@MainActivity, repo, uri) { count ->
                        runOnUiThread {
                            binding.tvProgressDetail.text = "Extracted $count files..."
                        }
                    }
                }
                binding.cardProgress.visibility = View.GONE
                refreshList()
                Toast.makeText(
                    this@MainActivity,
                    "Imported \"${imported.title}\" (${imported.fileCount} files)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                binding.cardProgress.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showRenameDialog(deck: SavedDeck) {
        val container = FrameLayout(this)
        val padH = (24 * resources.displayMetrics.density).toInt()
        val padV = (12 * resources.displayMetrics.density).toInt()
        container.setPadding(padH, padV, padH, padV)

        val input = EditText(this).apply {
            setText(deck.title)
            setSelection(deck.title.length)
            hint = getString(R.string.hint_name)
        }
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Deck")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty()) {
                    repo.renameDeck(deck.id, newName)
                    refreshList()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun openDeck(deck: SavedDeck) {
        val intent = Intent(this, DeckViewerActivity::class.java).apply {
            putExtra(DeckViewerActivity.EXTRA_DECK_ID, deck.id)
        }
        startActivity(intent)
    }

    private fun confirmResync(deck: SavedDeck) {
        if (deck.sourceUrl.startsWith("local://")) {
            Toast.makeText(this, "Imported decks have no remote URL to re-sync.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Re-sync \"${deck.title}\"?")
            .setMessage("This will re-download the latest version of this web deck from the server.")
            .setPositiveButton("Re-sync") { _, _ ->
                DeckDownloadService.start(
                    context = this,
                    url = deck.sourceUrl,
                    title = deck.title,
                    includeExternal = deck.includeExternalMedia,
                    existingDeckId = deck.id
                )
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmDelete(deck: SavedDeck) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"${deck.title}\"?")
            .setMessage("This frees ${DeckRepository.formatBytes(deck.totalBytes)} of offline storage.")
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                repo.deleteDeck(deck.id)
                refreshList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private var hasCheckedUpdateThisSession = false
    }
}
