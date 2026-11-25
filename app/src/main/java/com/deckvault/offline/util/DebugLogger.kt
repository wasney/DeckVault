package com.deckvault.offline.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {

    private const val MAX_LINES = 800
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    private var logFile: File? = null
    private var crashHandlerInstalled = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                val f = File(context.applicationContext.filesDir, "deckvault_debug.log")
                logFile = f
                if (f.exists()) {
                    runCatching {
                        val existing = f.readLines()
                        existing.takeLast(MAX_LINES).forEach { lines.addLast(it) }
                    }
                }
            }
            if (!crashHandlerInstalled) {
                crashHandlerInstalled = true
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    try {
                        val trace = throwable.stackTraceToString().take(3000)
                        log("CRASH", "Uncaught on thread '${thread.name}':\n$trace")
                    } catch (_: Exception) {
                    }
                    previous?.uncaughtException(thread, throwable)
                }
            }
        }
    }

    fun log(tag: String, message: String) {
        val entry = "[${timeFmt.format(Date())}] [$tag] $message"
        Log.d("DeckVault/$tag", message)
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(entry)
            persistLocked(entry)
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val suffix = if (throwable != null) " :: ${throwable.javaClass.simpleName}: ${throwable.message}" else ""
        log(tag, "ERROR: $message$suffix")
    }

    fun getLogsText(): String {
        synchronized(lock) {
            return if (lines.isEmpty()) "No debug logs recorded yet." else lines.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            runCatching { logFile?.writeText("") }
        }
    }

    private fun persistLocked(entry: String) {
        val f = logFile ?: return
        runCatching {
            if (f.exists() && f.length() > 384 * 1024) {
                f.writeText(lines.joinToString("\n") + "\n")
            } else {
                f.appendText(entry + "\n")
            }
        }
    }
}
