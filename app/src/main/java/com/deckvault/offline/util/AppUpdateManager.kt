package com.deckvault.offline.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.deckvault.offline.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkExpectedSize: Long,
    val isPreRelease: Boolean
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String, val latestTag: String?) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {

    const val GITHUB_REPO_URL = "https://github.com/wasney/DeckVault"
    const val GITHUB_RELEASES_URL = "https://github.com/wasney/DeckVault/releases"
    private const val RELEASES_API_URL = "https://api.github.com/repos/wasney/DeckVault/releases"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isCurrentBuildPreRelease(): Boolean {
        val v = BuildConfig.VERSION_NAME.lowercase()
        return v.contains("-beta") || v.contains("-pre") || v.contains("-alpha") || v.contains("-rc")
    }

    suspend fun checkForUpdate(includePreReleases: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        DebugLogger.log("Updater", "Checking GitHub Releases (current=$currentVersion, beta=$includePreReleases)")

        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DeckVault-Android/$currentVersion")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "GitHub returned HTTP ${response.code}"
                    DebugLogger.error("Updater", msg)
                    return@withContext UpdateCheckResult.Error(msg)
                }

                val body = response.body?.string().orEmpty()
                val releases = JSONArray(body)

                var bestRelease: GitHubReleaseInfo? = null
                for (i in 0 until releases.length()) {
                    val obj = releases.getJSONObject(i)
                    if (obj.optBoolean("draft", false)) continue
                    val isPre = obj.optBoolean("prerelease", false)
                    if (isPre && !includePreReleases) continue

                    val parsed = parseReleaseWithApk(obj, isPre) ?: continue
                    if (bestRelease == null || compareVersions(parsed.versionName, bestRelease.versionName) > 0) {
                        bestRelease = parsed
                    }
                }

                if (bestRelease == null) {
                    DebugLogger.log("Updater", "No published releases with APK assets found yet.")
                    return@withContext UpdateCheckResult.UpToDate(currentVersion, null)
                }

                val cmp = compareVersions(bestRelease.versionName, currentVersion)
                DebugLogger.log("Updater", "Latest=${bestRelease.tagName}, current=$currentVersion, cmp=$cmp")
                if (cmp > 0) {
                    UpdateCheckResult.UpdateAvailable(bestRelease)
                } else {
                    UpdateCheckResult.UpToDate(currentVersion, bestRelease.tagName)
                }
            }
        } catch (e: Exception) {
            DebugLogger.error("Updater", "Update check failed", e)
            UpdateCheckResult.Error(e.message ?: "Could not reach GitHub.")
        }
    }

    /** Hands off the direct GitHub Release APK download to the user's browser (avoids Play Protect dropper flags). */
    fun openReleaseDownloadInBrowser(context: Context, release: GitHubReleaseInfo) {
        val targetUrl = release.apkDownloadUrl.ifBlank { release.htmlUrl }
        DebugLogger.log("Updater", "Opening release APK in browser: $targetUrl")
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Downloading ${release.apkName} in browser — tap it when finished to update.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            DebugLogger.error("Updater", "Could not open browser for update", e)
            Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseReleaseWithApk(obj: JSONObject, isPre: Boolean): GitHubReleaseInfo? {
        val tagName = obj.optString("tag_name").trim()
        if (tagName.isEmpty()) return null
        val assets = obj.optJSONArray("assets") ?: return null

        var apkName: String? = null
        var apkUrl: String? = null
        var apkSize = 0L

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val dlUrl = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && apkUrl == null) {
                apkName = name
                apkUrl = dlUrl
                apkSize = asset.optLong("size", 0L)
            }
        }

        if (apkName == null || apkUrl == null) return null

        val cleanVersion = tagName.removePrefix("v").removePrefix("V")
        return GitHubReleaseInfo(
            tagName = tagName,
            versionName = cleanVersion,
            title = obj.optString("name").ifBlank { "DeckVault $tagName" },
            notes = obj.optString("body").ifBlank { "No release notes provided." },
            htmlUrl = obj.optString("html_url").ifBlank { GITHUB_RELEASES_URL },
            apkName = apkName,
            apkDownloadUrl = apkUrl,
            apkExpectedSize = apkSize,
            isPreRelease = isPre
        )
    }

    /** SemVer comparison where stable > pre-release and beta.10 > beta.2. */
    fun compareVersions(candidate: String, current: String): Int {
        val candClean = candidate.removePrefix("v").removePrefix("V")
        val currClean = current.removePrefix("v").removePrefix("V")

        val candBase = candClean.substringBefore('-')
        val currBase = currClean.substringBefore('-')
        val candPre = candClean.substringAfter('-', "")
        val currPre = currClean.substringAfter('-', "")

        val cParts = candBase.split('.').map { it.toIntOrNull() ?: 0 }
        val uParts = currBase.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(cParts.size, uParts.size)

        for (i in 0 until maxLen) {
            val c = cParts.getOrElse(i) { 0 }
            val u = uParts.getOrElse(i) { 0 }
            if (c != u) return c.compareTo(u)
        }

        if (candPre.isEmpty() && currPre.isEmpty()) return 0
        if (candPre.isEmpty()) return 1
        if (currPre.isEmpty()) return -1

        val cPreTokens = candPre.split('.', '-')
        val uPreTokens = currPre.split('.', '-')
        val preMax = maxOf(cPreTokens.size, uPreTokens.size)
        for (i in 0 until preMax) {
            val ct = cPreTokens.getOrNull(i) ?: return -1
            val ut = uPreTokens.getOrNull(i) ?: return 1
            val ci = ct.toIntOrNull()
            val ui = ut.toIntOrNull()
            val cmp = when {
                ci != null && ui != null -> ci.compareTo(ui)
                ci != null -> -1
                ui != null -> 1
                else -> ct.compareTo(ut, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
