package com.deckvault.offline.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.deckvault.offline.model.SavedDeck
import com.deckvault.offline.ui.DeckViewerActivity
import kotlin.math.abs

object ShortcutHelper {

    private val palette = intArrayOf(
        Color.parseColor("#1A56DB"),
        Color.parseColor("#0F766E"),
        Color.parseColor("#6D28D9"),
        Color.parseColor("#B45309"),
        Color.parseColor("#BE185D"),
        Color.parseColor("#0369A1")
    )

    fun pinDeckShortcut(context: Context, deck: SavedDeck) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "Your launcher does not support pinned shortcuts.", Toast.LENGTH_LONG).show()
            return
        }

        val launchIntent = Intent(context, DeckViewerActivity::class.java).apply {
            action = "com.deckvault.offline.OPEN_DECK"
            putExtra(DeckViewerActivity.EXTRA_DECK_ID, deck.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val iconBitmap = createDeckBadgeBitmap(deck.title, deck.id)
        val shortcut = ShortcutInfoCompat.Builder(context, "deck_${deck.id}")
            .setShortLabel(deck.title.take(22))
            .setLongLabel(deck.title)
            .setIcon(IconCompat.createWithBitmap(iconBitmap))
            .setIntent(launchIntent)
            .build()

        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        Toast.makeText(context, "Shortcut requested for \"${deck.title}\"", Toast.LENGTH_SHORT).show()
    }

    private fun createDeckBadgeBitmap(title: String, seed: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgColor = palette[abs(seed.hashCode()) % palette.size]
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(RectF(8f, 8f, size - 8f, size - 8f), 40f, 40f, bgPaint)

        val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val initials = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "DV"
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val yOffset = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initials, size / 2f, yOffset, textPaint)
        return bmp
    }
}
