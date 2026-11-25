package com.deckvault.offline.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.databinding.ItemDeckBinding
import com.deckvault.offline.model.SavedDeck
import com.deckvault.offline.optimizer.CompressionPreset

class DeckAdapter(
    private val onOpen: (SavedDeck) -> Unit,
    private val onPin: (SavedDeck) -> Unit,
    private val onMore: (SavedDeck, View) -> Unit
) : RecyclerView.Adapter<DeckAdapter.DeckViewHolder>() {

    private var items: List<SavedDeck> = emptyList()

    fun submitList(newItems: List<SavedDeck>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeckViewHolder {
        val binding = ItemDeckBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeckViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeckViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DeckViewHolder(private val binding: ItemDeckBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(deck: SavedDeck) {
            binding.tvDeckTitle.text = deck.title
            binding.tvDeckUrl.text = deck.sourceUrl
            val sizeText = DeckRepository.formatBytes(deck.totalBytes)

            val preset = CompressionPreset.fromId(deck.optimizationPreset)
            val optSuffix = if (preset != CompressionPreset.ORIGINAL) {
                val presetShort = preset.label.substringBefore(" (")
                if (deck.originalBytes > deck.totalBytes && deck.originalBytes > 0L) {
                    val pct = (((deck.originalBytes - deck.totalBytes) * 100L) / deck.originalBytes).toInt()
                    " • $presetShort (-$pct%)"
                } else {
                    " • $presetShort"
                }
            } else {
                ""
            }

            binding.tvDeckStats.text = "${deck.fileCount} files • $sizeText$optSuffix • Ready Offline"

            binding.root.setOnClickListener { onOpen(deck) }
            binding.btnOpen.setOnClickListener { onOpen(deck) }
            binding.btnPin.setOnClickListener { onPin(deck) }
            binding.btnMore.setOnClickListener { onMore(deck, it) }
        }
    }
}
