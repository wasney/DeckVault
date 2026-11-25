package com.deckvault.offline.model

import org.json.JSONObject

data class SavedDeck(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val baseDirUrl: String,
    val entryFile: String,
    val fileCount: Int,
    val totalBytes: Long,
    val savedAtMillis: Long,
    val includeExternalMedia: Boolean = true,
    val optimizationPreset: String = "original",
    val originalBytes: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("sourceUrl", sourceUrl)
        put("baseDirUrl", baseDirUrl)
        put("entryFile", entryFile)
        put("fileCount", fileCount)
        put("totalBytes", totalBytes)
        put("savedAtMillis", savedAtMillis)
        put("includeExternalMedia", includeExternalMedia)
        put("optimizationPreset", optimizationPreset)
        put("originalBytes", originalBytes)
    }

    companion object {
        fun fromJson(obj: JSONObject): SavedDeck = SavedDeck(
            id = obj.getString("id"),
            title = obj.getString("title"),
            sourceUrl = obj.getString("sourceUrl"),
            baseDirUrl = obj.getString("baseDirUrl"),
            entryFile = obj.optString("entryFile", "index.html"),
            fileCount = obj.optInt("fileCount", 0),
            totalBytes = obj.optLong("totalBytes", 0L),
            savedAtMillis = obj.optLong("savedAtMillis", System.currentTimeMillis()),
            includeExternalMedia = obj.optBoolean("includeExternalMedia", true),
            optimizationPreset = obj.optString("optimizationPreset", "original"),
            originalBytes = obj.optLong("originalBytes", 0L)
        )
    }
}
