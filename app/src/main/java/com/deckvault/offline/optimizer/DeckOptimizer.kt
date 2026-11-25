package com.deckvault.offline.optimizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.util.DebugLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

enum class CompressionPreset(
    val id: String,
    val label: String,
    val shortDescription: String,
    val compressImages: Boolean,
    val losslessWebp: Boolean,
    val webpQuality: Int,
    val maxImageDimension: Int,
    val compressVideos: Boolean,
    val maxVideoShortSide: Int,
    val videoBitrateBps: Int,
    val minVideoSizeBytes: Long,
    val gzipText: Boolean
) {
    ORIGINAL(
        id = "original",
        label = "Original (No Compression)",
        shortDescription = "Keep every downloaded file completely unmodified.",
        compressImages = false,
        losslessWebp = false,
        webpQuality = 100,
        maxImageDimension = Int.MAX_VALUE,
        compressVideos = false,
        maxVideoShortSide = Int.MAX_VALUE,
        videoBitrateBps = Int.MAX_VALUE,
        minVideoSizeBytes = Long.MAX_VALUE,
        gzipText = false
    ),
    LOSSLESS(
        id = "lossless",
        label = "Lossless",
        shortDescription = "Lossless WebP images + GZIP code/SVG. Videos untouched.",
        compressImages = true,
        losslessWebp = true,
        webpQuality = 100,
        maxImageDimension = Int.MAX_VALUE,
        compressVideos = false,
        maxVideoShortSide = Int.MAX_VALUE,
        videoBitrateBps = Int.MAX_VALUE,
        minVideoSizeBytes = Long.MAX_VALUE,
        gzipText = true
    ),
    BALANCED(
        id = "balanced",
        label = "Balanced (Recommended)",
        shortDescription = "Same-size WebP 82% + 1080p ~2.5 Mbps H.264 + GZIP text.",
        compressImages = true,
        losslessWebp = false,
        webpQuality = 82,
        maxImageDimension = Int.MAX_VALUE,
        compressVideos = true,
        maxVideoShortSide = 1080,
        videoBitrateBps = 2_500_000,
        minVideoSizeBytes = 4L * 1024L * 1024L,
        gzipText = true
    ),
    COMPACT(
        id = "compact",
        label = "Compact (Max Space Saver)",
        shortDescription = "Same-size WebP 70% + 720p ~1.5 Mbps H.264 + GZIP text.",
        compressImages = true,
        losslessWebp = false,
        webpQuality = 70,
        maxImageDimension = Int.MAX_VALUE,
        compressVideos = true,
        maxVideoShortSide = 720,
        videoBitrateBps = 1_500_000,
        minVideoSizeBytes = 2L * 1024L * 1024L,
        gzipText = true
    );

    companion object {
        fun fromId(id: String?): CompressionPreset =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ORIGINAL
    }
}

data class StorageCategoryStats(
    val fileCount: Int,
    val bytes: Long
)

data class DeckStorageBreakdown(
    val totalFiles: Int,
    val totalBytes: Long,
    val videos: StorageCategoryStats,
    val images: StorageCategoryStats,
    val pdfs: StorageCategoryStats,
    val textCode: StorageCategoryStats,
    val other: StorageCategoryStats,
    val webpOptimizedCount: Int,
    val gzipOptimizedCount: Int
)

data class OptimizationProgress(
    val phase: String,
    val currentStep: Int,
    val totalSteps: Int,
    val bytesSavedSoFar: Long
)

data class OptimizationResult(
    val bytesBefore: Long,
    val bytesAfter: Long,
    val fileCount: Int,
    val imagesConverted: Int,
    val videosCompressed: Int,
    val textGzipped: Int
)

class DeckOptimizer(private val context: Context) {

    private val imageExts = setOf("png", "jpg", "jpeg")
    private val videoExts = setOf("mp4", "m4v", "mov")
    private val textExts = setOf("html", "htm", "css", "js", "mjs", "json", "svg", "xml", "vtt")

    suspend fun optimizeDirectory(
        deckDir: File,
        preset: CompressionPreset,
        onProgress: (OptimizationProgress) -> Unit
    ): OptimizationResult {
        val allInitialFiles = deckDir.walkTopDown().filter { it.isFile }.toList()
        val bytesBefore = allInitialFiles.sumOf { it.length() }

        if (preset == CompressionPreset.ORIGINAL) {
            return OptimizationResult(bytesBefore, bytesBefore, allInitialFiles.size, 0, 0, 0)
        }

        val imageCandidates = if (preset.compressImages) {
            allInitialFiles.filter { f ->
                val name = f.name.lowercase()
                !name.endsWith(".webp") &&
                    !name.endsWith(".gz") &&
                    f.extension.lowercase() in imageExts &&
                    f.length() >= 2 * 1024L
            }
        } else {
            emptyList()
        }

        val videoCandidates = if (preset.compressVideos) {
            allInitialFiles.filter { f ->
                f.extension.lowercase() in videoExts && f.length() >= preset.minVideoSizeBytes
            }
        } else {
            emptyList()
        }

        val textCandidates = if (preset.gzipText) {
            allInitialFiles.filter { f ->
                val name = f.name.lowercase()
                !name.endsWith(".gz") &&
                    name != "deckvault-manifest.json" &&
                    f.extension.lowercase() in textExts &&
                    f.length() >= 1024L
            }
        } else {
            emptyList()
        }

        val totalSteps = (imageCandidates.size + videoCandidates.size + textCandidates.size).coerceAtLeast(1)
        var doneSteps = 0
        var savedBytes = 0L
        var imagesConverted = 0
        var videosCompressed = 0
        var textGzipped = 0

        DebugLogger.log(
            "Optimizer",
            "Starting ${preset.id} on ${deckDir.name}: before=${DeckRepository.formatBytes(bytesBefore)}, " +
                "imgs=${imageCandidates.size}, vids=${videoCandidates.size}, text=${textCandidates.size}"
        )

        // Phase 1: Images -> transparent .webp sidecar replacement
        for ((idx, imgFile) in imageCandidates.withIndex()) {
            coroutineContext.ensureActive()
            val saved = compressImageToWebp(imgFile, preset)
            if (saved > 0L) {
                savedBytes += saved
                imagesConverted++
            }
            doneSteps++
            if (idx == 0 || idx % 10 == 0 || idx == imageCandidates.lastIndex) {
                onProgress(
                    OptimizationProgress(
                        phase = "Optimizing images (${idx + 1}/${imageCandidates.size})",
                        currentStep = doneSteps,
                        totalSteps = totalSteps,
                        bytesSavedSoFar = savedBytes
                    )
                )
            }
        }

        // Phase 2: Videos -> Hardware Media3 H.264/AAC compression
        for ((idx, vidFile) in videoCandidates.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(
                OptimizationProgress(
                    phase = "Compressing video ${idx + 1}/${videoCandidates.size}: ${vidFile.name}",
                    currentStep = doneSteps,
                    totalSteps = totalSteps,
                    bytesSavedSoFar = savedBytes
                )
            )
            val saved = compressVideoWithMedia3(vidFile, preset)
            if (saved > 0L) {
                savedBytes += saved
                videosCompressed++
            }
            doneSteps++
            onProgress(
                OptimizationProgress(
                    phase = "Compressed video ${idx + 1}/${videoCandidates.size}",
                    currentStep = doneSteps,
                    totalSteps = totalSteps,
                    bytesSavedSoFar = savedBytes
                )
            )
        }

        // Phase 3: Text & SVG -> transparent .gz sidecar replacement
        for ((idx, txtFile) in textCandidates.withIndex()) {
            coroutineContext.ensureActive()
            val saved = compressTextToGzip(txtFile)
            if (saved > 0L) {
                savedBytes += saved
                textGzipped++
            }
            doneSteps++
            if (idx == 0 || idx % 15 == 0 || idx == textCandidates.lastIndex) {
                onProgress(
                    OptimizationProgress(
                        phase = "Compressing code & SVG (${idx + 1}/${textCandidates.size})",
                        currentStep = doneSteps,
                        totalSteps = totalSteps,
                        bytesSavedSoFar = savedBytes
                    )
                )
            }
        }

        val finalFiles = deckDir.walkTopDown().filter { it.isFile }.toList()
        val bytesAfter = finalFiles.sumOf { it.length() }

        DebugLogger.log(
            "Optimizer",
            "Finished ${preset.id}: ${DeckRepository.formatBytes(bytesBefore)} -> ${DeckRepository.formatBytes(bytesAfter)} " +
                "(imgs=$imagesConverted, vids=$videosCompressed, gz=$textGzipped)"
        )

        return OptimizationResult(
            bytesBefore = bytesBefore,
            bytesAfter = bytesAfter,
            fileCount = finalFiles.size,
            imagesConverted = imagesConverted,
            videosCompressed = videosCompressed,
            textGzipped = textGzipped
        )
    }

    private fun compressImageToWebp(sourceFile: File, preset: CompressionPreset): Long {
        if (!sourceFile.exists() || !sourceFile.isFile) return 0L
        val origSize = sourceFile.length()
        val tempOut = File(sourceFile.parentFile, "${sourceFile.name}.webp.tmp")
        val finalWebp = File(sourceFile.parentFile, "${sourceFile.name}.webp")

        return try {
            // Decode at exact 1:1 native pixel dimensions (never resize/downscale, so in5 absolute layouts stay 100% identical)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = 1
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmapToEncode = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts) ?: return 0L
            if (bitmapToEncode.width <= 0 || bitmapToEncode.height <= 0) {
                bitmapToEncode.recycle()
                return 0L
            }

            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (preset.losslessWebp) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            try {
                BufferedOutputStream(FileOutputStream(tempOut)).use { out ->
                    val ok = bitmapToEncode.compress(format, preset.webpQuality, out)
                    if (!ok) {
                        tempOut.delete()
                        return 0L
                    }
                }
            } finally {
                bitmapToEncode.recycle()
            }

            val newSize = tempOut.length()
            // Only keep WebP if it actually saves at least 5%
            if (newSize in 1 until (origSize * 95L / 100L)) {
                if (finalWebp.exists()) finalWebp.delete()
                if (tempOut.renameTo(finalWebp)) {
                    sourceFile.delete()
                    origSize - newSize
                } else {
                    tempOut.delete()
                    0L
                }
            } else {
                tempOut.delete()
                0L
            }
        } catch (_: OutOfMemoryError) {
            tempOut.delete()
            0L
        } catch (_: Exception) {
            tempOut.delete()
            0L
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun compressVideoWithMedia3(sourceFile: File, preset: CompressionPreset): Long {
        if (!sourceFile.exists() || !sourceFile.isFile) return 0L
        val origSize = sourceFile.length()

        val meta = readVideoMetadata(sourceFile) ?: return 0L
        val shortSide = min(meta.displayWidth, meta.displayHeight)
        val needsResize = shortSide > preset.maxVideoShortSide
        val needsBitrateDrop = meta.bitrateBps <= 0L || meta.bitrateBps > (preset.videoBitrateBps * 1.25).toLong()

        if (!needsResize && !needsBitrateDrop) {
            DebugLogger.log("Optimizer", "Skipping already-compact video ${sourceFile.name} (${shortSide}p, ${meta.bitrateBps / 1000} kbps)")
            return 0L
        }

        val targetHeight = if (needsResize) {
            val scale = preset.maxVideoShortSide.toFloat() / shortSide.toFloat()
            (((meta.displayHeight * scale).roundToInt() / 2) * 2).coerceAtLeast(240)
        } else {
            ((meta.displayHeight / 2) * 2).coerceAtLeast(240)
        }

        val tempOut = File(sourceFile.parentFile, "${sourceFile.name}.opt.mp4")
        if (tempOut.exists()) tempOut.delete()

        return try {
            val succeeded = runMedia3Transform(
                inputFile = sourceFile,
                outputFile = tempOut,
                targetHeight = targetHeight,
                targetBitrateBps = preset.videoBitrateBps
            )
            if (!succeeded || !tempOut.exists() || tempOut.length() == 0L) {
                tempOut.delete()
                return 0L
            }

            val newSize = tempOut.length()
            // Require at least 10% reduction before replacing original
            if (newSize < (origSize * 90L / 100L)) {
                val backup = File(sourceFile.parentFile, "${sourceFile.name}.bak")
                if (backup.exists()) backup.delete()
                if (sourceFile.renameTo(backup)) {
                    if (tempOut.renameTo(sourceFile)) {
                        backup.delete()
                        DebugLogger.log(
                            "Optimizer",
                            "Video shrunk: ${sourceFile.name} ${DeckRepository.formatBytes(origSize)} -> ${DeckRepository.formatBytes(newSize)}"
                        )
                        origSize - newSize
                    } else {
                        backup.renameTo(sourceFile)
                        tempOut.delete()
                        0L
                    }
                } else {
                    tempOut.delete()
                    0L
                }
            } else {
                DebugLogger.log(
                    "Optimizer",
                    "Kept original video ${sourceFile.name} (transcoded size ${DeckRepository.formatBytes(newSize)} vs ${DeckRepository.formatBytes(origSize)})"
                )
                tempOut.delete()
                0L
            }
        } catch (e: CancellationException) {
            tempOut.delete()
            throw e
        } catch (e: Exception) {
            tempOut.delete()
            DebugLogger.error("Optimizer", "Video compression skipped for ${sourceFile.name}", e)
            0L
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun runMedia3Transform(
        inputFile: File,
        outputFile: File,
        targetHeight: Int,
        targetBitrateBps: Int
    ): Boolean = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var transformerRef: Transformer? = null

        mainHandler.post {
            if (!cont.isActive) return@post
            try {
                val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(targetBitrateBps)
                            .build()
                    )
                    .build()

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        DebugLogger.error("Media3", "Export error on ${inputFile.name}", exportException)
                        if (cont.isActive) cont.resume(false)
                    }
                }

                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(listener)
                    .build()

                transformerRef = transformer

                val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
                    .build()

                transformer.start(editedItem, outputFile.absolutePath)
            } catch (e: Exception) {
                DebugLogger.error("Media3", "Failed to start Transformer on ${inputFile.name}", e)
                if (cont.isActive) cont.resume(false)
            }
        }

        cont.invokeOnCancellation {
            mainHandler.post {
                runCatching { transformerRef?.cancel() }
                runCatching { outputFile.delete() }
            }
        }
    }

    private fun compressTextToGzip(sourceFile: File): Long {
        if (!sourceFile.exists() || !sourceFile.isFile) return 0L
        val origSize = sourceFile.length()
        val tempOut = File(sourceFile.parentFile, "${sourceFile.name}.gz.tmp")
        val finalGz = File(sourceFile.parentFile, "${sourceFile.name}.gz")

        return try {
            BufferedInputStream(FileInputStream(sourceFile)).use { input ->
                GZIPOutputStream(BufferedOutputStream(FileOutputStream(tempOut))).use { gzip ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        gzip.write(buf, 0, read)
                    }
                }
            }
            val newSize = tempOut.length()
            if (newSize in 1 until (origSize * 90L / 100L)) {
                if (finalGz.exists()) finalGz.delete()
                if (tempOut.renameTo(finalGz)) {
                    sourceFile.delete()
                    origSize - newSize
                } else {
                    tempOut.delete()
                    0L
                }
            } else {
                tempOut.delete()
                0L
            }
        } catch (_: Exception) {
            tempOut.delete()
            0L
        }
    }

    private data class VideoMeta(
        val displayWidth: Int,
        val displayHeight: Int,
        val bitrateBps: Long
    )

    private fun readVideoMetadata(file: File): VideoMeta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            if (w <= 0 || h <= 0) return null
            if (rot == 90 || rot == 270) VideoMeta(h, w, br) else VideoMeta(w, h, br)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        fun analyzeDeckStorage(deckDir: File): DeckStorageBreakdown {
            var totalFiles = 0
            var totalBytes = 0L
            var vidCount = 0; var vidBytes = 0L
            var imgCount = 0; var imgBytes = 0L
            var pdfCount = 0; var pdfBytes = 0L
            var txtCount = 0; var txtBytes = 0L
            var othCount = 0; var othBytes = 0L
            var webpOptimized = 0
            var gzipOptimized = 0

            val videoSet = setOf("mp4", "m4v", "mov", "webm", "ogv")
            val imageSet = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico")
            val textSet = setOf("html", "htm", "css", "js", "mjs", "json", "svg", "xml", "vtt")

            deckDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val len = f.length()
                totalFiles++
                totalBytes += len

                val lower = f.name.lowercase()
                val effectiveExt = when {
                    lower.endsWith(".gz") -> {
                        gzipOptimized++
                        lower.removeSuffix(".gz").substringAfterLast('.', "")
                    }
                    lower.endsWith(".png.webp") || lower.endsWith(".jpg.webp") || lower.endsWith(".jpeg.webp") -> {
                        webpOptimized++
                        "webp"
                    }
                    else -> f.extension.lowercase()
                }

                when {
                    effectiveExt in videoSet -> { vidCount++; vidBytes += len }
                    effectiveExt in imageSet -> { imgCount++; imgBytes += len }
                    effectiveExt == "pdf" -> { pdfCount++; pdfBytes += len }
                    effectiveExt in textSet -> { txtCount++; txtBytes += len }
                    else -> { othCount++; othBytes += len }
                }
            }

            return DeckStorageBreakdown(
                totalFiles = totalFiles,
                totalBytes = totalBytes,
                videos = StorageCategoryStats(vidCount, vidBytes),
                images = StorageCategoryStats(imgCount, imgBytes),
                pdfs = StorageCategoryStats(pdfCount, pdfBytes),
                textCode = StorageCategoryStats(txtCount, txtBytes),
                other = StorageCategoryStats(othCount, othBytes),
                webpOptimizedCount = webpOptimized,
                gzipOptimizedCount = gzipOptimized
            )
        }
    }
}
