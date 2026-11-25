package com.deckvault.offline.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.deckvault.offline.R
import com.deckvault.offline.ui.SettingsActivity
import com.deckvault.offline.util.AppUpdateManager
import com.deckvault.offline.util.AppUpdatePrefs
import com.deckvault.offline.util.DebugLogger
import com.deckvault.offline.util.UpdateCheckResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppUpdatePrefs.isCheckOnBootEnabled(context)) return

        DebugLogger.init(context)
        DebugLogger.log("BootUpdater", "Device booted — checking GitHub Releases for updates...")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val includePre = AppUpdatePrefs.isIncludePreReleases(context)
                when (val result = AppUpdateManager.checkForUpdate(includePre)) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        postUpdateNotification(context, result.release.tagName, result.release.title)
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                DebugLogger.error("BootUpdater", "Boot update check failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postUpdateNotification(context: Context, tag: String, title: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "deckvault_updates"
        val channel = NotificationChannel(
            channelId,
            "App Updates",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val openIntent = Intent(context, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_AUTO_CHECK, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context,
            5010,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DeckVault Update Available ($tag)")
            .setContentText("Tap to review and install $title from GitHub Releases.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching { manager.notify(5011, notif) }
    }
}
