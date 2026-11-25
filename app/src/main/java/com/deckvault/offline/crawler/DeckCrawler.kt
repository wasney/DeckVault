package com.deckvault.offline.crawler

import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.model.SavedDeck
import com.deckvault.offline.util.DebugLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class CrawlProgress(
    val phase: String,
    val downloadedFiles: Int,
    val totalDiscovered: Int,
    val downloadedBytes: Long
)

class DeckCrawler {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val mediaAndDocExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "ico", "bmp",
        "mp4", "webm", "m4v", "mov", "ogv", "mp3", "wav", "ogg", "m4a",
        "pdf", "woff", "woff2", "ttf", "otf", "eot", "css", "js", "json", "xml", "vtt"
    )

    private val htmlExtensions = setOf("html", "htm", "xhtml")

    private val skippedJsLibs = listOf(
        "jquery", "hammer", "modernizr", "gsap", "tweenmax", "polyfill", "bootstrap.bundle"
    )

    private val lazyAttributes = listOf(
        "src", "data-src", "data-aniurl", "data-original", "data-lazy", "data-bg",
        "data-poster", "data-video", "data-url", "data-href", "poster", "data"
    )

    private val eventAttributes = listOf(
        "onclick", "ontouchend", "data-click", "data-action"
    )

    private val genericErrorTitles = setOf(
        "error", "error page", "404", "404 not found", "not found", "page not found", "access denied", "forbidden"
    )

    private val cssUrlRegex = Regex("""url\(\s*['"]?([^'")]+?)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val cssImportRegex = Regex("""@import\s+(?:url\(\s*)?['"]([^'"]+)['"]\s*\)?""", RegexOption.IGNORE_CASE)

    private val jsAssetPathRegex = Regex(
        """['"]((?:https?://|\.\./|\./|/|assets/|images/|media/|video/|videos/|audio/|css/|js/|fonts/|pdfs?/)[^'"\s<>{}*\\]{1,260}\.(?:png|jpe?g|gif|svg|webp|avif|ico|mp4|webm|m4v|mp3|wav|ogg|m4a|pdf|woff2?|ttf|otf|eot|css|js|json|html?|vtt)(?:\?[^'"\s]*)?)['"]""",
        RegexOption.IGNORE_CASE
    )

    private val jsSimpleMediaRegex = Regex(
        """['"]([a-zA-Z0-9_\-/ .%]+\.(?:png|jpe?g|gif|svg|webp|mp4|webm|mp3|pdf|woff2?|css|js|json))['"]""",
        RegexOption.IGNORE_CASE
    )

    suspend fun crawlDeck(
        deckId: String,
        sourceUrlString: String,
        customTitle: String?,
        includeExternalMedia: Boolean,
        outputDir: File,
        onProgress: (CrawlProgress) -> Unit
    ): SavedDeck {
        val startUrl = normalizeUrl(sourceUrlString)
            ?: throw IllegalArgumentException("Invalid URL. Make sure it starts with http:// or https://")

        val baseDirUrl = computeBaseDirUrl(startUrl)
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        DebugLogger.log("Crawler", "Starting crawl id=$deckId url=$startUrl base=$baseDirUrl")

        val visitedUrls = ConcurrentHashMap.newKeySet<String>()
        val urlToLocalPath = ConcurrentHashMap<String, String>()
        val downloadedCount = AtomicInteger(0)
        val skippedSoft404Count = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val detectedTitle = arrayOf(customTitle?.takeIf { it.isNotBlank() } ?: deriveFallbackTitle(startUrl))

        val entryLocalPath = computeLocalRelPath(startUrl, baseDirUrl, forceHtml = true)
        val normalizedStart = stripFragment(startUrl)
        urlToLocalPath[normalizedStart] = entryLocalPath

        var currentFrontier = listOf(startUrl to 0)
        visitedUrls.add(normalizedStart)

        val semaphore = Semaphore(6)
        val maxFiles = 5000

        onProgress(CrawlProgress("Analyzing entry page...", 0, 1, 0L))

        while (currentFrontier.isNotEmpty() && downloadedCount.get() < maxFiles) {
            coroutineContext.ensureActive()
            val nextFrontier = ConcurrentHashMap<String, Pair<HttpUrl, Int>>()

            coroutineScope {
                currentFrontier.map { (url, depth) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            coroutineContext.ensureActive()
                            if (downloadedCount.get() >= maxFiles) return@withPermit

                            val cleanUrlStr = stripFragment(url)
                            val isEntryPage = (cleanUrlStr == normalizedStart)
                            val localRelPath = urlToLocalPath[cleanUrlStr]
                                ?: computeLocalRelPath(url, baseDirUrl, forceHtml = false).also {
                                    urlToLocalPath[cleanUrlStr] = it
                                }

                            val targetFile = File(outputDir, localRelPath)
                            if (targetFile.exists() && targetFile.length() > 0L) {
                                return@withPermit
                            }

                            val expectedExt = getExtension(localRelPath)
                            val downloaded = downloadFile(
                                url, targetFile, expectedExt, isEntryPage,
                                onSoft404 = { skippedSoft404Count.incrementAndGet() },
                                onFail = {
                                    val c = failedCount.incrementAndGet()
                                    if (c <= 10) DebugLogger.log("Crawler", "Skipped/failed: $url")
                                }
                            ) ?: return@withPermit

                            val done = downloadedCount.incrementAndGet()
                            val bytesNow = totalBytes.addAndGet(downloaded.bytesWritten)

                            if (done == 1 || done % 4 == 0) {
                                onProgress(
                                    CrawlProgress(
                                        phase = "Saving assets ($done/${visitedUrls.size})",
                                        downloadedFiles = done,
                                        totalDiscovered = visitedUrls.size,
                                        downloadedBytes = bytesNow
                                    )
                                )
                            }

                            val contentType = downloaded.contentType.lowercase()
                            val isHtml = (contentType.contains("text/html") || expectedExt in htmlExtensions) &&
                                (isEntryPage || expectedExt.isEmpty() || expectedExt in htmlExtensions)
                            val isCss = contentType.contains("text/css") || expectedExt == "css"
                            val isJs = contentType.contains("javascript") || expectedExt == "js"

                            if (isHtml && depth <= 2) {
                                val rawHtml = runCatching { targetFile.readText(Charsets.UTF_8) }.getOrNull()
                                if (rawHtml != null) {
                                    val rewritten = processHtml(
                                        rawHtml = rawHtml,
                                        pageUrl = url,
                                        baseDirUrl = baseDirUrl,
                                        includeExternalMedia = includeExternalMedia,
                                        captureTitle = (isEntryPage && customTitle.isNullOrBlank()),
                                        onTitleFound = { t ->
                                            if (t.lowercase() !in genericErrorTitles) {
                                                detectedTitle[0] = t
                                            }
                                        },
                                        onDiscover = { resolved, isSubPage ->
                                            val key = stripFragment(resolved)
                                            if (visitedUrls.size < maxFiles && visitedUrls.add(key)) {
                                                val rel = computeLocalRelPath(resolved, baseDirUrl, forceHtml = isSubPage)
                                                urlToLocalPath[key] = rel
                                                nextFrontier[key] = resolved to (if (isSubPage) depth + 1 else depth)
                                            }
                                        }
                                    )
                                    targetFile.writeText(rewritten, Charsets.UTF_8)
                                }
                            } else if (isCss) {
                                val rawCss = runCatching { targetFile.readText(Charsets.UTF_8) }.getOrNull()
                                if (rawCss != null) {
                                    val rewritten = processCss(
                                        rawCss = rawCss,
                                        cssUrl = url,
                                        baseDirUrl = baseDirUrl,
                                        includeExternalMedia = includeExternalMedia,
                                        onDiscover = { resolved ->
                                            val key = stripFragment(resolved)
                                            if (visitedUrls.size < maxFiles && visitedUrls.add(key)) {
                                                val rel = computeLocalRelPath(resolved, baseDirUrl, forceHtml = false)
                                                urlToLocalPath[key] = rel
                                                nextFrontier[key] = resolved to depth
                                            }
                                        }
                                    )
                                    targetFile.writeText(rewritten, Charsets.UTF_8)
                                }
                            } else if (isJs) {
                                val fileName = targetFile.name.lowercase()
                                val isThirdPartyLib = skippedJsLibs.any { fileName.contains(it) }
                                if (!isThirdPartyLib && targetFile.length() < 3 * 1024 * 1024) {
                                    val rawJs = runCatching { targetFile.readText(Charsets.UTF_8) }.getOrNull()
                                    if (rawJs != null) {
                                        val rewritten = processJs(
                                            rawJs = rawJs,
                                            jsUrl = url,
                                            entryBaseUrl = baseDirUrl,
                                            includeExternalMedia = includeExternalMedia,
                                            onDiscover = { resolved ->
                                                val key = stripFragment(resolved)
                                                if (visitedUrls.size < maxFiles && visitedUrls.add(key)) {
                                                    val rel = computeLocalRelPath(resolved, baseDirUrl, forceHtml = false)
                                                    urlToLocalPath[key] = rel
                                                    nextFrontier[key] = resolved to depth
                                                }
                                            }
                                        )
                                        targetFile.writeText(rewritten, Charsets.UTF_8)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            currentFrontier = nextFrontier.values.toList()
        }

        val manifestFile = File(outputDir, "deckvault-manifest.json")
        if (!manifestFile.exists()) {
            val safeName = detectedTitle[0].replace("\"", "'")
            manifestFile.writeText(
                """{"name":"$safeName","short_name":"$safeName","start_url":"/$entryLocalPath","display":"standalone","background_color":"#000000","theme_color":"#1A56DB"}"""
            )
        }

        DebugLogger.log(
            "Crawler",
            "Completed '${detectedTitle[0]}': saved=${downloadedCount.get()}, discovered=${visitedUrls.size}, " +
                "soft404=${skippedSoft404Count.get()}, failed=${failedCount.get()}, size=${DeckRepository.formatBytes(totalBytes.get())}"
        )

        onProgress(
            CrawlProgress(
                phase = "Finalizing offline deck...",
                downloadedFiles = downloadedCount.get(),
                totalDiscovered = visitedUrls.size,
                downloadedBytes = totalBytes.get()
            )
        )

        return SavedDeck(
            id = deckId,
            title = detectedTitle[0],
            sourceUrl = startUrl.toString(),
            baseDirUrl = baseDirUrl.toString(),
            entryFile = entryLocalPath,
            fileCount = downloadedCount.get(),
            totalBytes = totalBytes.get(),
            savedAtMillis = System.currentTimeMillis(),
            includeExternalMedia = includeExternalMedia
        )
    }

    private fun processHtml(
        rawHtml: String,
        pageUrl: HttpUrl,
        baseDirUrl: HttpUrl,
        includeExternalMedia: Boolean,
        captureTitle: Boolean,
        onTitleFound: (String) -> Unit,
        onDiscover: (HttpUrl, Boolean) -> Unit
    ): String {
        val doc = Jsoup.parse(rawHtml, pageUrl.toString())
        if (captureTitle) {
            val t = doc.title().trim()
            if (t.isNotEmpty()) onTitleFound(t)
        }

        val replacements = LinkedHashMap<String, String>()

        fun handleAttrValue(rawVal: String, isLinkTag: Boolean, isAnchorTag: Boolean, isIframe: Boolean) {
            val trimmed = rawVal.trim()
            if (!isFetchableReference(trimmed)) return
            val resolved = resolveUrlSafe(pageUrl, trimmed) ?: return
            val ext = getExtension(resolved.encodedPath)
            val insideBase = isInsideBaseDir(resolved, baseDirUrl)

            if (isAnchorTag) {
                val isDocOrMedia = ext in mediaAndDocExtensions
                val isLocalHtml = insideBase && (ext.isEmpty() || ext in htmlExtensions)
                if (!isDocOrMedia && !isLocalHtml) return
                if (!insideBase && !includeExternalMedia) return
                onDiscover(resolved, isLocalHtml)
            } else {
                if (!insideBase && !includeExternalMedia && !isLinkTag && ext !in setOf("css", "js")) {
                    if (resolved.host != baseDirUrl.host) return
                }
                val isSubPage = isIframe || (insideBase && ext in htmlExtensions)
                onDiscover(resolved, isSubPage)
            }

            if (!insideBase) {
                val localPath = "/" + computeLocalRelPath(resolved, baseDirUrl, forceHtml = isIframe)
                replacements[trimmed] = localPath
            }
        }

        for (el in doc.allElements) {
            val tag = el.tagName().lowercase()
            val isAnchor = tag == "a"
            val isLink = tag == "link"
            val isIframe = tag == "iframe"

            for (attr in lazyAttributes) {
                if (el.hasAttr(attr)) {
                    handleAttrValue(el.attr(attr), isLink, isAnchor, isIframe)
                }
            }
            for (evAttr in eventAttributes) {
                if (el.hasAttr(evAttr)) {
                    val evVal = el.attr(evAttr)
                    jsAssetPathRegex.findAll(evVal).forEach { match ->
                        handleAttrValue(match.groupValues[1], false, false, false)
                    }
                }
            }
            if (el.hasAttr("href")) {
                val relAttr = el.attr("rel").lowercase()
                if (!isLink || relAttr.contains("stylesheet") || relAttr.contains("icon") || relAttr.contains("preload") || relAttr.contains("manifest")) {
                    handleAttrValue(el.attr("href"), isLink, isAnchor, isIframe)
                }
            }
            for (srcsetAttr in listOf("srcset", "data-srcset")) {
                if (el.hasAttr(srcsetAttr)) {
                    val entries = el.attr(srcsetAttr).split(",")
                    for (entry in entries) {
                        val candidate = entry.trim().split(Regex("\\s+")).firstOrNull() ?: continue
                        handleAttrValue(candidate, false, false, false)
                    }
                }
            }
            if (el.hasAttr("style")) {
                val styleVal = el.attr("style")
                cssUrlRegex.findAll(styleVal).forEach { match ->
                    handleAttrValue(match.groupValues[1], false, false, false)
                }
            }
        }

        for (styleEl in doc.select("style")) {
            cssUrlRegex.findAll(styleEl.data()).forEach { match ->
                handleAttrValue(match.groupValues[1], false, false, false)
            }
        }

        for (scriptEl in doc.select("script")) {
            val jsText = scriptEl.data()
            if (jsText.isBlank()) continue
            jsAssetPathRegex.findAll(jsText).forEach { match ->
                handleAttrValue(match.groupValues[1], false, false, false)
            }
        }

        var result = rawHtml
        for ((orig, localReplacement) in replacements) {
            if (orig.length >= 4 && orig != localReplacement) {
                result = result.replace("\"$orig\"", "\"$localReplacement\"")
                result = result.replace("'$orig'", "'$localReplacement'")
            }
        }
        return result
    }

    private fun processCss(
        rawCss: String,
        cssUrl: HttpUrl,
        baseDirUrl: HttpUrl,
        includeExternalMedia: Boolean,
        onDiscover: (HttpUrl) -> Unit
    ): String {
        val replacements = LinkedHashMap<String, String>()

        fun handleCssRef(rawRef: String) {
            val trimmed = rawRef.trim()
            if (!isFetchableReference(trimmed)) return
            val resolved = resolveUrlSafe(cssUrl, trimmed) ?: return
            val insideBase = isInsideBaseDir(resolved, baseDirUrl)
            if (!insideBase && !includeExternalMedia && resolved.host != baseDirUrl.host) {
                val ext = getExtension(resolved.encodedPath)
                if (ext !in setOf("css", "woff", "woff2", "ttf", "otf")) return
            }
            onDiscover(resolved)
            if (!insideBase) {
                replacements[trimmed] = "/" + computeLocalRelPath(resolved, baseDirUrl, forceHtml = false)
            }
        }

        cssUrlRegex.findAll(rawCss).forEach { handleCssRef(it.groupValues[1]) }
        cssImportRegex.findAll(rawCss).forEach { handleCssRef(it.groupValues[1]) }

        var result = rawCss
        for ((orig, repl) in replacements) {
            if (orig.length >= 4 && orig != repl) {
                result = result.replace(orig, repl)
            }
        }
        return result
    }

    private fun processJs(
        rawJs: String,
        jsUrl: HttpUrl,
        entryBaseUrl: HttpUrl,
        includeExternalMedia: Boolean,
        onDiscover: (HttpUrl) -> Unit
    ): String {
        val replacements = LinkedHashMap<String, String>()

        fun handleJsRef(rawRef: String) {
            val trimmed = rawRef.trim()
            if (!isFetchableReference(trimmed)) return
            val baseForResolve = if (trimmed.startsWith("assets/") || !trimmed.startsWith(".")) entryBaseUrl else jsUrl
            val resolved = resolveUrlSafe(baseForResolve, trimmed) ?: return
            val insideBase = isInsideBaseDir(resolved, entryBaseUrl)
            if (!insideBase && !includeExternalMedia && resolved.host != entryBaseUrl.host) return
            onDiscover(resolved)
            if (!insideBase) {
                replacements[trimmed] = "/" + computeLocalRelPath(resolved, entryBaseUrl, forceHtml = false)
            }
        }

        jsAssetPathRegex.findAll(rawJs).forEach { handleJsRef(it.groupValues[1]) }
        jsSimpleMediaRegex.findAll(rawJs).forEach { match ->
            val candidate = match.groupValues[1]
            if (candidate.contains("/") && !candidate.startsWith("//")) {
                handleJsRef(candidate)
            }
        }

        var result = rawJs
        for ((orig, repl) in replacements) {
            if (orig.length >= 6 && orig != repl) {
                result = result.replace("\"$orig\"", "\"$repl\"")
                result = result.replace("'$orig'", "'$repl'")
            }
        }
        return result
    }

    private data class DownloadOutcome(val bytesWritten: Long, val contentType: String)

    private fun downloadFile(
        url: HttpUrl,
        destination: File,
        expectedExt: String,
        isEntryPage: Boolean,
        onSoft404: () -> Unit,
        onFail: () -> Unit
    ): DownloadOutcome? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onFail()
                    return null
                }
                val body = response.body ?: return null
                val ct = response.header("Content-Type") ?: ""
                val ctLower = ct.lowercase()

                if (!isEntryPage && expectedExt in mediaAndDocExtensions && ctLower.contains("text/html")) {
                    onSoft404()
                    return null
                }

                destination.parentFile?.mkdirs()
                val tempFile = File(destination.parentFile, "${destination.name}.part")
                var written = 0L
                try {
                    body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                written += read
                            }
                        }
                    }
                    if (destination.exists()) destination.delete()
                    tempFile.renameTo(destination)
                    DownloadOutcome(written, ct)
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onFail()
            null
        }
    }

    private fun isFetchableReference(ref: String): Boolean {
        if (ref.isEmpty() || ref.length > 500) return false
        val lower = ref.lowercase()
        if (lower.startsWith("data:") ||
            lower.startsWith("blob:") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("tel:") ||
            lower.startsWith("#") ||
            lower.contains("w3.org/2000/svg") ||
            lower.contains("w3.org/1999/xhtml") ||
            lower.contains("schemas.openxmlformats")
        ) {
            return false
        }
        if (ref.contains("{") || ref.contains("}") || ref.contains("<") || ref.contains(">") || ref.contains("\n")) {
            return false
        }
        return true
    }

    private fun resolveUrlSafe(base: HttpUrl, rawRef: String): HttpUrl? {
        val sanitized = rawRef.replace(" ", "%20")
        return try {
            val resolved = base.resolve(sanitized) ?: return null
            if (resolved.scheme != "http" && resolved.scheme != "https") return null
            resolved
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUrl(input: String): HttpUrl? {
        val trimmed = input.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.toHttpUrlOrNull()
    }

    private fun computeBaseDirUrl(url: HttpUrl): HttpUrl {
        val path = url.encodedPath
        if (path.endsWith("/")) return url.newBuilder().query(null).fragment(null).build()
        val lastSlash = path.lastIndexOf('/')
        val dirPath = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else "/"
        return url.newBuilder()
            .encodedPath(dirPath)
            .query(null)
            .fragment(null)
            .build()
    }

    private fun isInsideBaseDir(url: HttpUrl, baseDirUrl: HttpUrl): Boolean {
        if (url.scheme != baseDirUrl.scheme || url.host != baseDirUrl.host || url.port != baseDirUrl.port) {
            return false
        }
        return url.encodedPath.startsWith(baseDirUrl.encodedPath)
    }

    private fun computeLocalRelPath(url: HttpUrl, baseDirUrl: HttpUrl, forceHtml: Boolean): String {
        val insideBase = isInsideBaseDir(url, baseDirUrl)
        val rawRel = if (insideBase) {
            var rel = url.encodedPath.removePrefix(baseDirUrl.encodedPath).trimStart('/')
            if (rel.isEmpty() || rel.endsWith("/")) {
                rel += "index.html"
            }
            rel
        } else {
            val hostPart = sanitizeSegment(url.host)
            var pathPart = url.encodedPath.trimStart('/')
            if (pathPart.isEmpty() || pathPart.endsWith("/")) {
                pathPart += "index.html"
            }
            "_ext/$hostPart/$pathPart"
        }

        val decoded = try {
            URLDecoder.decode(rawRel, "UTF-8")
        } catch (_: Exception) {
            rawRel
        }

        val cleanSegments = decoded
            .split("/")
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { sanitizeSegment(it) }
            .toMutableList()

        if (cleanSegments.isEmpty()) {
            cleanSegments.add("index.html")
        }

        val queryStr = url.query
        if (!insideBase && !queryStr.isNullOrBlank()) {
            val last = cleanSegments.last()
            val dotIdx = last.lastIndexOf('.')
            val hash = queryStr.hashCode().toUInt().toString(16)
            cleanSegments[cleanSegments.lastIndex] = if (dotIdx > 0) {
                "${last.substring(0, dotIdx)}_$hash${last.substring(dotIdx)}"
            } else if (forceHtml) {
                "${last}_$hash.html"
            } else {
                "${last}_$hash"
            }
        } else if (forceHtml && !cleanSegments.last().contains(".")) {
            cleanSegments[cleanSegments.lastIndex] = "${cleanSegments.last()}.html"
        }

        return cleanSegments.joinToString("/")
    }

    private fun sanitizeSegment(segment: String): String {
        return segment.replace(Regex("""[<>:"\\|?*]"""), "_")
    }

    private fun stripFragment(url: HttpUrl): String {
        return url.newBuilder().fragment(null).build().toString()
    }

    private fun getExtension(path: String): String {
        val clean = path.substringBefore('?').substringBefore('#')
        val lastDot = clean.lastIndexOf('.')
        val lastSlash = clean.lastIndexOf('/')
        if (lastDot <= lastSlash || lastDot == -1) return ""
        return clean.substring(lastDot + 1).lowercase()
    }

    private fun deriveFallbackTitle(url: HttpUrl): String {
        val segments = url.pathSegments.filter { it.isNotBlank() && !it.equals("index.html", true) }
        val raw = segments.lastOrNull() ?: url.host
        return raw.replace("_", " ").replace("-", " ").replaceFirstChar { it.uppercase() }
    }
}
