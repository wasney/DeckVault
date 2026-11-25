package com.deckvault.offline.util

import android.content.Context
import android.net.Uri
import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.model.SavedDeck
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DeckShareHelper {

    private const val META_ENTRY_NAME = "deckvault_meta.json"

    fun exportDeckToZip(
        context: Context,
        repo: DeckRepository,
        deck: SavedDeck,
        onProgress: (Int, Int) -> Unit
    ): File {
        val shareDir = File(context.cacheDir, "shared_decks").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val safeName = deck.title
            .replace(Regex("""[^a-zA-Z0-9_\- ]"""), "_")
            .trim()
            .replace(Regex("""\s+"""), "_")
            .take(40)
            .ifEmpty { "Deck_${deck.id}" }

        val outZip = File(shareDir, "${safeName}.deckvault.zip")
        val deckDir = repo.getDeckDir(deck.id)
        val allFiles = deckDir.walkTopDown().filter { it.isFile }.toList()
        val total = allFiles.size.coerceAtLeast(1)

        DebugLogger.log("Share", "Exporting '${deck.title}' ($total files) -> ${outZip.name}")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outZip))).use { zos ->
            val metaBytes = deck.toJson().toString(2).toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry(META_ENTRY_NAME))
            zos.write(metaBytes)
            zos.closeEntry()

            val buffer = ByteArray(32 * 1024)
            allFiles.forEachIndexed { idx, file ->
                val relPath = file.relativeTo(deckDir).invariantSeparatorsPath
                val entry = ZipEntry("files/$relPath")
                zos.putNextEntry(entry)
                BufferedInputStream(FileInputStream(file)).use { bis ->
                    while (true) {
                        val read = bis.read(buffer)
                        if (read == -1) break
                        zos.write(buffer, 0, read)
                    }
                }
                zos.closeEntry()
                if (idx == 0 || idx % 20 == 0 || idx == total - 1) {
                    onProgress(idx + 1, total)
                }
            }
        }

        DebugLogger.log("Share", "Export finished: ${DeckRepository.formatBytes(outZip.length())}")
        return outZip
    }

    fun importDeckFromUri(
        context: Context,
        repo: DeckRepository,
        uri: Uri,
        onProgress: (Int) -> Unit
    ): SavedDeck {
        val newId = UUID.randomUUID().toString().take(8)
        val deckDir = repo.getDeckDir(newId).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val canonicalRoot = deckDir.canonicalFile
        var parsedMeta: JSONObject? = null
        var fileCount = 0
        var totalBytes = 0L

        DebugLogger.log("Import", "Importing deck bundle from $uri into $newId")

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open selected file.")

            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                val buffer = ByteArray(32 * 1024)
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace("\\", "/").trimStart('/')
                    if (!entry.isDirectory && name.isNotEmpty() && !name.contains("..")) {
                        if (name == META_ENTRY_NAME) {
                            val metaText = zis.readBytes().toString(Charsets.UTF_8)
                            parsedMeta = runCatching { JSONObject(metaText) }.getOrNull()
                        } else {
                            val relPath = if (name.startsWith("files/")) name.removePrefix("files/") else name
                            val destFile = File(canonicalRoot, relPath).canonicalFile
                            if (destFile.path.startsWith(canonicalRoot.path)) {
                                destFile.parentFile?.mkdirs()
                                BufferedOutputStream(FileOutputStream(destFile)).use { bos ->
                                    while (true) {
                                        val read = zis.read(buffer)
                                        if (read == -1) break
                                        bos.write(buffer, 0, read)
                                        totalBytes += read
                                    }
                                }
                                fileCount++
                                if (fileCount == 1 || fileCount % 25 == 0) {
                                    onProgress(fileCount)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (fileCount == 0) {
                throw IllegalStateException("The selected ZIP did not contain any web deck files.")
            }

            val entryFile = parsedMeta?.optString("entryFile")?.takeIf {
                it.isNotBlank() && (File(deckDir, it).exists() || File(deckDir, "$it.gz").exists())
            } ?: detectEntryHtml(deckDir)

            val title = parsedMeta?.optString("title")?.takeIf { it.isNotBlank() } ?: "Imported Deck"
            val sourceUrl = parsedMeta?.optString("sourceUrl")?.takeIf { it.isNotBlank() } ?: "local://imported"
            val baseDirUrl = parsedMeta?.optString("baseDirUrl")?.takeIf { it.isNotBlank() } ?: ""
            val optPreset = parsedMeta?.optString("optimizationPreset", "original") ?: "original"
            val origBytes = parsedMeta?.optLong("originalBytes", 0L) ?: 0L

            val saved = SavedDeck(
                id = newId,
                title = title,
                sourceUrl = sourceUrl,
                baseDirUrl = baseDirUrl,
                entryFile = entryFile,
                fileCount = fileCount,
                totalBytes = totalBytes,
                savedAtMillis = System.currentTimeMillis(),
                includeExternalMedia = true,
                optimizationPreset = optPreset,
                originalBytes = origBytes
            )
            repo.upsertDeck(saved)
            DebugLogger.log("Import", "Imported '${saved.title}' ($fileCount files, ${DeckRepository.formatBytes(totalBytes)}, entry=$entryFile)")
            return saved
        } catch (e: Exception) {
            deckDir.deleteRecursively()
            DebugLogger.error("Import", "Import failed", e)
            throw e
        }
    }

    private fun detectEntryHtml(deckDir: File): String {
        if (File(deckDir, "index.html").exists() || File(deckDir, "index.html.gz").exists()) {
            return "index.html"
        }
        val firstHtml = deckDir.walkTopDown()
            .filter {
                val n = it.name.lowercase().removeSuffix(".gz")
                it.isFile && (n.endsWith(".html") || n.endsWith(".htm"))
            }
            .minByOrNull { it.relativeTo(deckDir).invariantSeparatorsPath.count { ch -> ch == '/' } }
        return firstHtml?.relativeTo(deckDir)?.invariantSeparatorsPath?.removeSuffix(".gz") ?: "index.html"
    }
}
