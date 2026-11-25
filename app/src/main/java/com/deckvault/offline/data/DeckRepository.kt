package com.deckvault.offline.data

import android.content.Context
import com.deckvault.offline.model.SavedDeck
import org.json.JSONArray
import java.io.File

class DeckRepository(private val context: Context) {

    private val decksRoot: File
        get() = File(context.filesDir, "decks").apply { if (!exists()) mkdirs() }

    private val indexFile: File
        get() = File(context.filesDir, "decks_index.json")

    fun getDeckDir(deckId: String): File = File(decksRoot, deckId)

    @Synchronized
    fun getAllDecks(): List<SavedDeck> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length())
                .map { SavedDeck.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.savedAtMillis }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getDeckById(deckId: String): SavedDeck? {
        return getAllDecks().firstOrNull { it.id == deckId }
    }

    @Synchronized
    fun upsertDeck(deck: SavedDeck) {
        val current = getAllDecks().filterNot { it.id == deck.id }.toMutableList()
        current.add(0, deck)
        saveList(current)
    }

    @Synchronized
    fun renameDeck(deckId: String, newTitle: String) {
        val existing = getDeckById(deckId) ?: return
        val trimmed = newTitle.trim()
        if (trimmed.isNotEmpty()) {
            upsertDeck(existing.copy(title = trimmed))
        }
    }

    @Synchronized
    fun deleteDeck(deckId: String) {
        val updated = getAllDecks().filterNot { it.id == deckId }
        saveList(updated)
        getDeckDir(deckId).deleteRecursively()
    }

    @Synchronized
    fun recalculateDeckStats(deckId: String): SavedDeck? {
        val deck = getDeckById(deckId) ?: return null
        val dir = getDeckDir(deckId)
        var count = 0
        var bytes = 0L
        dir.walkTopDown().filter { it.isFile }.forEach {
            count++
            bytes += it.length()
        }
        val updated = deck.copy(fileCount = count, totalBytes = bytes)
        upsertDeck(updated)
        return updated
    }

    private fun saveList(list: List<SavedDeck>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        indexFile.writeText(arr.toString(2))
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format("%.1f MB", mb)
            return String.format("%.2f GB", mb / 1024.0)
        }
    }
}
