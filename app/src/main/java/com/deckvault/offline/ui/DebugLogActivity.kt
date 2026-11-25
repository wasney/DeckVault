package com.deckvault.offline.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.deckvault.offline.databinding.ActivityDebugLogBinding
import com.deckvault.offline.util.DebugLogger

class DebugLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.init(this)
        binding = ActivityDebugLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.logRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnCloseLogs.setOnClickListener { finish() }
        binding.btnCopyLogs.setOnClickListener {
            val text = DebugLogger.getLogsText()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DeckVault Debug Log", text))
            Toast.makeText(this, "Debug log copied to clipboard.", Toast.LENGTH_SHORT).show()
        }
        binding.btnClearLogs.setOnClickListener {
            DebugLogger.clear()
            loadLogs()
        }

        loadLogs()
    }

    private fun loadLogs() {
        binding.tvLogContent.text = DebugLogger.getLogsText()
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
