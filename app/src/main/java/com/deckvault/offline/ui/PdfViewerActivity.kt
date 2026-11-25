package com.deckvault.offline.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deckvault.offline.databinding.ActivityPdfViewerBinding
import com.deckvault.offline.databinding.ItemPdfPageBinding
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.pdfRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val path = intent.getStringExtra(EXTRA_PDF_PATH)
        val title = intent.getStringExtra(EXTRA_PDF_TITLE) ?: "Offline PDF"
        binding.tvPdfTitle.text = title
        binding.btnClosePdf.setOnClickListener { finish() }

        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            Toast.makeText(this, "PDF file not found offline.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            fileDescriptor = fd
            pdfRenderer = renderer

            binding.rvPdfPages.layoutManager = LinearLayoutManager(this)
            binding.rvPdfPages.adapter = PdfPageAdapter(renderer)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open PDF: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private class PdfPageAdapter(private val renderer: PdfRenderer) :
        RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val b = ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return PageViewHolder(b)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val page = renderer.openPage(position)
            val maxWidth = 1600f
            val scale = (maxWidth / page.width.coerceAtLeast(1)).coerceIn(1f, 2f)
            val targetW = (page.width * scale).toInt().coerceAtLeast(1)
            val targetH = (page.height * scale).toInt().coerceAtLeast(1)

            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            holder.itemBinding.ivPdfPage.setImageBitmap(bmp)
        }

        override fun getItemCount(): Int = renderer.pageCount

        class PageViewHolder(val itemBinding: ItemPdfPageBinding) :
            RecyclerView.ViewHolder(itemBinding.root)
    }

    companion object {
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        const val EXTRA_PDF_TITLE = "extra_pdf_title"
    }
}
