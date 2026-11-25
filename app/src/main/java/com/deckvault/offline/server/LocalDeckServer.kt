package com.deckvault.offline.server

import com.deckvault.offline.model.SavedDeck
import com.deckvault.offline.util.DebugLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

class LocalDeckServer(
    private val deckDir: File,
    private val deck: SavedDeck,
    private val onAssetCaughtUp: (() -> Unit)? = null
) {
    @Volatile
    var catchUpEnabled: Boolean = false

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val requestCounter = AtomicInteger(0)

    @Volatile
    private var running = false

    private val catchUpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val port: Int
        get() = serverSocket?.localPort ?: -1

    /** Must be called off the Main (UI) Thread. */
    fun start(): Int {
        if (running && port > 0) return port
        val ipv4Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val socket = ServerSocket(0, 64, ipv4Loopback)
        socket.reuseAddress = true
        serverSocket = socket
        running = true
        val pool = Executors.newCachedThreadPool()
        executor = pool

        val entryExists = File(deckDir, deck.entryFile).exists() || File(deckDir, "${deck.entryFile}.gz").exists()
        DebugLogger.log(
            "Server",
            "Bound to http://${socket.inetAddress.hostAddress}:${socket.localPort} " +
                "(entry=${deck.entryFile}, exists=$entryExists, preset=${deck.optimizationPreset})"
        )

        pool.execute {
            while (running) {
                try {
                    val client = socket.accept()
                    pool.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (running) {
                        DebugLogger.error("Server", "Accept loop error", e)
                    } else {
                        break
                    }
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        running = false
        val sock = serverSocket
        serverSocket = null
        val pool = executor
        executor = null
        try {
            sock?.close()
        } catch (_: Exception) {
        }
        try {
            pool?.shutdownNow()
        } catch (_: Exception) {
        }
        DebugLogger.log("Server", "Stopped local server for '${deck.title}'")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val out = client.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val rawTarget = parts[1]

                var rangeHeader: String? = null
                var acceptEncoding = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    } else if (line.startsWith("Accept-Encoding:", ignoreCase = true)) {
                        acceptEncoding = line.substringAfter(":").trim().lowercase()
                    }
                }

                if (method == "OPTIONS") {
                    val cors = buildString {
                        append("HTTP/1.1 204 No Content\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                        append("Access-Control-Allow-Headers: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(cors.toByteArray())
                    out.flush()
                    return
                }

                if (method != "GET" && method != "HEAD") {
                    sendSimpleResponse(out, 405, "Method Not Allowed", "Method Not Allowed")
                    return
                }

                val rawPath = rawTarget.substringBefore('?')
                val rawQuery = if (rawTarget.contains('?')) rawTarget.substringAfter('?') else null
                val decodedPath = try {
                    URLDecoder.decode(rawPath, "UTF-8")
                } catch (_: Exception) {
                    rawPath
                }

                val normalizedRel = decodedPath
                    .trimStart('/')
                    .ifEmpty { deck.entryFile }

                val canonicalRoot = deckDir.canonicalFile
                var fileToServe = resolveSafeFile(canonicalRoot, normalizedRel)
                if (fileToServe == null) {
                    DebugLogger.log("Server", "403 Forbidden: $normalizedRel")
                    sendSimpleResponse(out, 403, "Forbidden", "Forbidden")
                    return
                }

                if (fileToServe.isDirectory) {
                    fileToServe = File(fileToServe, "index.html")
                }

                var isGzippedSidecar = false
                var overrideMime: String? = null

                // Check transparent optimized sidecars: .webp for converted PNG/JPG, .gz for text/SVG
                if (!fileToServe.exists()) {
                    val webpSidecar = resolveSafeFile(canonicalRoot, "${normalizedRel}.webp")
                    val gzSidecar = resolveSafeFile(canonicalRoot, "${normalizedRel}.gz")
                    when {
                        webpSidecar != null && webpSidecar.exists() && webpSidecar.isFile -> {
                            fileToServe = webpSidecar
                            overrideMime = "image/webp"
                        }
                        gzSidecar != null && gzSidecar.exists() && gzSidecar.isFile -> {
                            fileToServe = gzSidecar
                            isGzippedSidecar = true
                            overrideMime = guessMimeType(normalizedRel)
                        }
                        !rawQuery.isNullOrBlank() -> {
                            val hash = rawQuery.hashCode().toUInt().toString(16)
                            val dotIdx = normalizedRel.lastIndexOf('.')
                            val hashedRel = if (dotIdx > normalizedRel.lastIndexOf('/')) {
                                "${normalizedRel.substring(0, dotIdx)}_$hash${normalizedRel.substring(dotIdx)}"
                            } else {
                                "${normalizedRel}_$hash"
                            }
                            val candidate = resolveSafeFile(canonicalRoot, hashedRel)
                            val candidateGz = resolveSafeFile(canonicalRoot, "${hashedRel}.gz")
                            val candidateWebp = resolveSafeFile(canonicalRoot, "${hashedRel}.webp")
                            when {
                                candidate != null && candidate.exists() && candidate.isFile -> fileToServe = candidate
                                candidateWebp != null && candidateWebp.exists() && candidateWebp.isFile -> {
                                    fileToServe = candidateWebp
                                    overrideMime = "image/webp"
                                }
                                candidateGz != null && candidateGz.exists() && candidateGz.isFile -> {
                                    fileToServe = candidateGz
                                    isGzippedSidecar = true
                                    overrideMime = guessMimeType(normalizedRel)
                                }
                            }
                        }
                    }
                }

                if (!fileToServe.exists() && catchUpEnabled) {
                    if (tryCatchUpDownload(normalizedRel, fileToServe)) {
                        DebugLogger.log("CatchUp", "Fetched missing asset: $normalizedRel")
                        onAssetCaughtUp?.invoke()
                    }
                }

                if (!fileToServe.exists() || !fileToServe.isFile) {
                    DebugLogger.log("Server", "404 Not Found: $normalizedRel")
                    sendSimpleResponse(out, 404, "Not Found", "Not Found: $normalizedRel")
                    return
                }

                val count = requestCounter.incrementAndGet()
                if (count <= 10 || normalizedRel.endsWith(".html", true) || normalizedRel.endsWith(".mp4", true) || normalizedRel.endsWith(".pdf", true)) {
                    val modeTag = when {
                        isGzippedSidecar -> " [gz]"
                        overrideMime == "image/webp" -> " [webp]"
                        else -> ""
                    }
                    DebugLogger.log("Server", "200/206 $method /$normalizedRel$modeTag (${fileToServe.length()} B${if (rangeHeader != null) ", $rangeHeader" else ""})")
                }

                val mime = overrideMime ?: guessMimeType(fileToServe.name)
                if (isGzippedSidecar) {
                    serveGzippedFile(out, fileToServe, mime, method == "HEAD", acceptEncoding.contains("gzip"))
                } else {
                    serveFile(out, fileToServe, mime, method == "HEAD", rangeHeader)
                }
            }
        } catch (e: Exception) {
            if (running && e !is java.net.SocketException) {
                DebugLogger.error("Server", "Client handler error", e)
            }
        }
    }

    private fun resolveSafeFile(canonicalRoot: File, relPath: String): File? {
        val candidate = File(canonicalRoot, relPath).canonicalFile
        return if (candidate.path.startsWith(canonicalRoot.path)) candidate else null
    }

    private fun tryCatchUpDownload(relPath: String, destination: File): Boolean {
        if (deck.baseDirUrl.isBlank()) return false
        val encodedRel = relPath.trimStart('/').replace(" ", "%20")
        val remoteUrl = if (encodedRel.startsWith("_ext/")) {
            "https://${encodedRel.removePrefix("_ext/")}"
        } else {
            val base = deck.baseDirUrl.trimEnd('/')
            "$base/$encodedRel"
        }

        return try {
            val req = Request.Builder().url(remoteUrl).build()
            catchUpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                destination.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun serveGzippedFile(
        out: OutputStream,
        gzFile: File,
        mime: String,
        headOnly: Boolean,
        clientAcceptsGzip: Boolean
    ) {
        if (clientAcceptsGzip) {
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mime\r\n")
                append("Content-Encoding: gzip\r\n")
                append("Vary: Accept-Encoding\r\n")
                append("Content-Length: ${gzFile.length()}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray())
            if (!headOnly) {
                gzFile.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            out.flush()
        } else {
            val plainBytes = GZIPInputStream(gzFile.inputStream()).use { it.readBytes() }
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mime\r\n")
                append("Content-Length: ${plainBytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray())
            if (!headOnly) out.write(plainBytes)
            out.flush()
        }
    }

    private fun serveFile(out: OutputStream, file: File, mime: String, headOnly: Boolean, rangeHeader: String?) {
        val totalLength = file.length()

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalLength > 0) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").substringBefore(",")
            val bounds = rangeSpec.split("-")
            val start = bounds.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = bounds.getOrNull(1)?.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)

            if (start >= totalLength || start > end) {
                val headers = buildString {
                    append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                    append("Content-Range: bytes */$totalLength\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray())
                out.flush()
                return
            }

            val chunkLength = end - start + 1
            val headers = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: $mime\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Range: bytes $start-$end/$totalLength\r\n")
                append("Content-Length: $chunkLength\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray())
            if (!headOnly) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(16 * 1024)
                    var remaining = chunkLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            out.flush()
            return
        }

        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $totalLength\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        if (!headOnly) {
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
            }
        }
        out.flush()
    }

    private fun sendSimpleResponse(out: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun guessMimeType(name: String): String {
        val cleanName = name.removeSuffix(".gz")
        return when (cleanName.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "ico" -> "image/x-icon"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "xml" -> "application/xml"
            "vtt" -> "text/vtt"
            else -> "application/octet-stream"
        }
    }
}
