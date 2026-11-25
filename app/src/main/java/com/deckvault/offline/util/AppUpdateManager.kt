package com.deckvault.offline.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.deckvault.offline.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkExpectedSize: Long,
    val sha256DownloadUrl: String?,
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
        .readTimeout(60, TimeUnit.SECONDS)
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

    suspend fun downloadAndVerifyApk(
        context: Context,
        release: GitHubReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val targetFile = File(updateDir, release.apkName)
        val partFile = File(updateDir, "${release.apkName}.part")

        DebugLogger.log("Updater", "Downloading ${release.apkName} from ${release.apkDownloadUrl}")

        val req = Request.Builder()
            .url(release.apkDownloadUrl)
            .header("User-Agent", "DeckVault-Android/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("APK download failed (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw IllegalStateException("Empty APK response body")
            val total = body.contentLength().takeIf { it > 0L } ?: release.apkExpectedSize
            var downloaded = 0L

            body.byteStream().use { input ->
                partFile.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var lastEmit = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 120L || downloaded == total) {
                            lastEmit = now
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
        }

        if (partFile.length() == 0L) {
            partFile.delete()
            throw IllegalStateException("Downloaded APK file is empty.")
        }

        if (release.apkExpectedSize > 0L && partFile.length() != release.apkExpectedSize) {
            val got = partFile.length()
            partFile.delete()
            throw IllegalStateException("Size mismatch: expected ${release.apkExpectedSize} bytes, got $got bytes.")
        }

        if (!release.sha256DownloadUrl.isNullOrBlank()) {
            val expectedHash = fetchExpectedSha256(release.sha256DownloadUrl)
            if (expectedHash != null) {
                val actualHash = computeSha256(partFile)
                DebugLogger.log("Updater", "SHA-256 verify expected=$expectedHash actual=$actualHash")
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    partFile.delete()
                    throw IllegalStateException("APK integrity verification failed (SHA-256 mismatch).")
                }
            }
        }

        val validApk = runCatching {
            ZipFile(partFile).use { zip ->
                zip.getEntry("AndroidManifest.xml") != null
            }
        }.getOrDefault(false)

        if (!validApk) {
            partFile.delete()
            throw IllegalStateException("Downloaded file is not a valid Android APK.")
        }

        if (targetFile.exists()) targetFile.delete()
        partFile.renameTo(targetFile)
        DebugLogger.log("Updater", "APK downloaded and verified: ${targetFile.name} (${targetFile.length()} B)")
        targetFile
    }

    fun launchApkInstaller(activity: Activity, apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Allow App Installs")
                .setMessage("Android requires permission for DeckVault to install APK updates. Enable \"Allow from this source\", then return to install.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(settingsIntent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "com.deckvault.offline.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(installIntent)
    }

    private fun parseReleaseWithApk(obj: JSONObject, isPre: Boolean): GitHubReleaseInfo? {
        val tagName = obj.optString("tag_name").trim()
        if (tagName.isEmpty()) return null
        val assets = obj.optJSONArray("assets") ?: return null

        var apkName: String? = null
        var apkUrl: String? = null
        var apkSize = 0L
        var shaUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val dlUrl = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && apkUrl == null) {
                apkName = name
                apkUrl = dlUrl
                apkSize = asset.optLong("size", 0L)
            } else if (name.endsWith(".sha256", ignoreCase = true) && shaUrl == null) {
                shaUrl = dlUrl
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
            sha256DownloadUrl = shaUrl,
            isPreRelease = isPre
        )
    }

    private fun fetchExpectedSha256(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "DeckVault-Android/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string()?.trim().orEmpty()
                val token = text.split(Regex("\\s+")).firstOrNull().orEmpty()
                if (token.matches(Regex("^[0-9a-fA-F]{64}$"))) token else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
