package com.deckvault.offline.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.deckvault.offline.BuildConfig
import com.deckvault.offline.R
import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.databinding.ActivitySettingsBinding
import com.deckvault.offline.databinding.DialogOptimizeDeckBinding
import com.deckvault.offline.optimizer.CompressionPreset
import com.deckvault.offline.service.DeckOptimizationService
import com.deckvault.offline.util.AppUpdateManager
import com.deckvault.offline.util.AppUpdatePrefs
import com.deckvault.offline.util.DebugLogger
import com.deckvault.offline.util.GitHubReleaseInfo
import com.deckvault.offline.util.UpdateCheckResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repo: DeckRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.init(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = DeckRepository(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnBackSettings.setOnClickListener { finish() }

        // Compression preset UI
        refreshPresetSummary()
        binding.btnChangeDefaultPreset.setOnClickListener { showDefaultPresetPicker() }
        binding.btnOptimizeAll.setOnClickListener { showBatchOptimizeDialog() }

        // Update preferences
        val isBetaBuild = AppUpdateManager.isCurrentBuildPreRelease()
        binding.switchCheckLaunch.isChecked = AppUpdatePrefs.isCheckOnLaunchEnabled(this)
        binding.switchIncludePrerelease.isChecked = AppUpdatePrefs.isIncludePreReleases(this)
        updateChannelBadge(isBetaBuild, binding.switchIncludePrerelease.isChecked)

        binding.switchCheckLaunch.setOnCheckedChangeListener { _, checked ->
            AppUpdatePrefs.setCheckOnLaunchEnabled(this, checked)
        }
        binding.switchIncludePrerelease.setOnCheckedChangeListener { _, checked ->
            AppUpdatePrefs.setIncludePreReleases(this, checked)
            updateChannelBadge(isBetaBuild, checked)
        }

        // About info
        val channelLabel = if (isBetaBuild) " [Beta]" else " [Official]"
        binding.tvAboutVersion.text =
            "DeckVault v${BuildConfig.VERSION_NAME}$channelLabel (Build ${BuildConfig.VERSION_CODE})"
        binding.tvUpdateStatus.text =
            "Installed: v${BuildConfig.VERSION_NAME} • Tap below to check GitHub Releases."

        binding.btnCheckUpdates.setOnClickListener { runManualUpdateCheck() }
        binding.btnOpenReleases.setOnClickListener { openUrl(AppUpdateManager.GITHUB_RELEASES_URL) }
        binding.btnOpenGithub.setOnClickListener { openUrl(AppUpdateManager.GITHUB_REPO_URL) }
        binding.tvAboutRepoUrl.setOnClickListener { openUrl(AppUpdateManager.GITHUB_REPO_URL) }
        binding.btnViewDebugLog.setOnClickListener {
            startActivity(Intent(this, DebugLogActivity::class.java))
        }
    }

    private fun updateChannelBadge(isBetaBuild: Boolean, betaOptIn: Boolean) {
        val currentText = if (isBetaBuild) "Running Beta build" else "Running Official build"
        val channelText = if (betaOptIn) "Subscribed to Beta + Official releases" else "Subscribed to Official releases only"
        binding.tvChannelBadge.text = "$currentText • $channelText"
    }

    private fun refreshPresetSummary() {
        val preset = CompressionPreset.fromId(AppUpdatePrefs.getDefaultCompressionPresetId(this))
        binding.tvDefaultPresetSummary.text = "${preset.label} — ${preset.shortDescription}"
    }

    private fun showDefaultPresetPicker() {
        val presets = CompressionPreset.entries
        val labels = presets.map { "${it.label}\n${it.shortDescription}" }.toTypedArray()
        val currentIdx = presets.indexOf(
            CompressionPreset.fromId(AppUpdatePrefs.getDefaultCompressionPresetId(this))
        ).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Default Preset for New Downloads")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val chosen = presets[which]
                AppUpdatePrefs.setDefaultCompressionPresetId(this, chosen.id)
                refreshPresetSummary()
                Toast.makeText(this, "Default set to ${chosen.label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showBatchOptimizeDialog() {
        val uncompressed = repo.getAllDecks().filter {
            CompressionPreset.fromId(it.optimizationPreset) == CompressionPreset.ORIGINAL
        }
        val targets = if (uncompressed.isNotEmpty()) uncompressed else repo.getAllDecks()
        if (targets.isEmpty()) {
            Toast.makeText(this, "No saved decks to optimize yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val dlgBinding = DialogOptimizeDeckBinding.inflate(LayoutInflater.from(this))
        val totalBytes = targets.sumOf { it.totalBytes }
        dlgBinding.tvOptimizeSummary.text =
            "Ready to optimize ${targets.size} deck(s) totaling ${DeckRepository.formatBytes(totalBytes)}. " +
                "Each deck stays self-contained in its own folder."

        MaterialAlertDialogBuilder(this)
            .setTitle("Optimize Existing Decks")
            .setView(dlgBinding.root)
            .setPositiveButton("Start Batch") { _, _ ->
                val preset = when (dlgBinding.rgPreset.checkedRadioButtonId) {
                    R.id.rb_compact -> CompressionPreset.COMPACT
                    R.id.rb_lossless -> CompressionPreset.LOSSLESS
                    else -> CompressionPreset.BALANCED
                }
                val keepCopy = dlgBinding.switchKeepCopy.isChecked
                DeckOptimizationService.start(
                    context = this,
                    deckIds = targets.map { it.id },
                    preset = preset,
                    createCopy = keepCopy
                )
                Toast.makeText(this, "Started optimizing ${targets.size} deck(s) in background.", Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun runManualUpdateCheck() {
        binding.btnCheckUpdates.isEnabled = false
        binding.updateProgressBar.visibility = View.VISIBLE
        binding.updateProgressBar.isIndeterminate = true
        binding.tvUpdateStatus.text = "Checking GitHub Releases..."

        val includePre = binding.switchIncludePrerelease.isChecked
        lifecycleScope.launch {
            when (val result = AppUpdateManager.checkForUpdate(includePre)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    binding.btnCheckUpdates.isEnabled = true
                    binding.updateProgressBar.visibility = View.GONE
                    val rel = result.release
                    binding.tvUpdateStatus.text = "Update available: ${rel.tagName} (${DeckRepository.formatBytes(rel.apkExpectedSize)})"
                    showUpdateAvailableDialog(rel)
                }

                is UpdateCheckResult.UpToDate -> {
                    binding.btnCheckUpdates.isEnabled = true
                    binding.updateProgressBar.visibility = View.GONE
                    val suffix = if (result.latestTag != null) " (Latest in channel: ${result.latestTag})" else ""
                    binding.tvUpdateStatus.text = "You're up to date on v${result.currentVersion}$suffix."
                    Toast.makeText(this@SettingsActivity, "DeckVault is up to date.", Toast.LENGTH_SHORT).show()
                }

                is UpdateCheckResult.Error -> {
                    binding.btnCheckUpdates.isEnabled = true
                    binding.updateProgressBar.visibility = View.GONE
                    binding.tvUpdateStatus.text = "Update check failed: ${result.message}"
                    Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(release: GitHubReleaseInfo) {
        val sizeStr = if (release.apkExpectedSize > 0L) {
            " (${DeckRepository.formatBytes(release.apkExpectedSize)})"
        } else {
            ""
        }
        val preBadge = if (release.isPreRelease) " [Beta]" else ""
        val message = "New version ${release.tagName}$preBadge$sizeStr is ready.\n\n${release.notes}"

        MaterialAlertDialogBuilder(this)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Download in Browser") { _, _ ->
                AppUpdateManager.openReleaseDownloadInBrowser(this, release)
            }
            .setNeutralButton("Release Page") { _, _ ->
                openUrl(release.htmlUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
