package com.deckvault.offline.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.deckvault.offline.R
import com.deckvault.offline.data.DeckRepository
import com.deckvault.offline.databinding.ActivityViewerBinding
import com.deckvault.offline.model.SavedDeck
import com.deckvault.offline.server.LocalDeckServer
import com.deckvault.offline.util.DebugLogger
import com.deckvault.offline.util.ShortcutHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

class DeckViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var repo: DeckRepository
    private var server: LocalDeckServer? = null
    private var currentDeck: SavedDeck? = null
    private var localBaseUrl: String = ""

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.init(this)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = DeckRepository(this)
        hideSystemBars()
        configureWebView()

        binding.btnFloatingMenu.setOnClickListener { showFloatingMenu(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    hideCustomFullscreenView()
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })

        loadDeckFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadDeckFromIntent(intent)
    }

    private fun loadDeckFromIntent(launchIntent: Intent?) {
        val deckId = launchIntent?.getStringExtra(EXTRA_DECK_ID)
        val deck = deckId?.let { repo.getDeckById(it) }
        if (deck == null) {
            DebugLogger.error("Viewer", "Saved deck not found for id=$deckId")
            Toast.makeText(this, "Saved deck not found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (currentDeck?.id == deck.id && server != null) {
            return
        }

        val previousServer = server
        server = null
        currentDeck = deck

        DebugLogger.log("Viewer", "Opening deck '${deck.title}' (id=${deck.id}, files=${deck.fileCount})")

        lifecycleScope.launch {
            try {
                val deckDir = repo.getDeckDir(deck.id)
                val (localServer, port) = withContext(Dispatchers.IO) {
                    previousServer?.stop()
                    val srv = LocalDeckServer(deckDir, deck) {
                        repo.recalculateDeckStats(deck.id)
                    }
                    val boundPort = srv.start()
                    srv to boundPort
                }

                if (isFinishing || isDestroyed) {
                    withContext(Dispatchers.IO) { localServer.stop() }
                    return@launch
                }

                server = localServer
                localBaseUrl = "http://127.0.0.1:$port"
                val targetUrl = "$localBaseUrl/${deck.entryFile}"
                DebugLogger.log("Viewer", "Loading WebView URL: $targetUrl")

                binding.webView.clearHistory()
                binding.webView.loadUrl(targetUrl)
            } catch (e: Exception) {
                DebugLogger.error("Viewer", "Failed to start local server", e)
                if (!isFinishing && !isDestroyed) {
                    MaterialAlertDialogBuilder(this@DeckViewerActivity)
                        .setTitle("Could not start offline viewer")
                        .setMessage(e.stackTraceToString().take(800))
                        .setPositiveButton("Close") { _, _ -> finish() }
                        .show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportMultipleWindows(true)
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleNavigationUrl(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                DebugLogger.log("WebView", "Page finished: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                DebugLogger.error(
                    "WebView",
                    "Load error (${error?.errorCode}) ${error?.description} on ${request?.url}"
                )
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                DebugLogger.log(
                    "WebView",
                    "HTTP ${errorResponse?.statusCode} on ${request?.url}"
                )
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    DebugLogger.log(
                        "JS",
                        "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val tempWebView = WebView(this@DeckViewerActivity)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val target = req?.url ?: return true
                        if (!handleNavigationUrl(target)) {
                            binding.webView.loadUrl(target.toString())
                        }
                        tempWebView.destroy()
                        return true
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                hideCustomFullscreenView()
            }
        }

        binding.webView.setDownloadListener { url, _, _, _, _ ->
            handleNavigationUrl(Uri.parse(url))
        }
    }

    private fun handleNavigationUrl(uri: Uri): Boolean {
        val urlStr = uri.toString()
        val pathLower = (uri.path ?: "").lowercase()

        if (pathLower.endsWith(".pdf") && localBaseUrl.isNotEmpty() && urlStr.startsWith(localBaseUrl)) {
            val deck = currentDeck ?: return false
            val relPath = URLDecoder.decode(uri.path?.trimStart('/') ?: "", "UTF-8")
            val pdfFile = File(repo.getDeckDir(deck.id), relPath)
            if (pdfFile.exists()) {
                val intent = Intent(this, PdfViewerActivity::class.java).apply {
                    putExtra(PdfViewerActivity.EXTRA_PDF_PATH, pdfFile.absolutePath)
                    putExtra(PdfViewerActivity.EXTRA_PDF_TITLE, pdfFile.name)
                }
                startActivity(intent)
                return true
            }
        }

        if (localBaseUrl.isNotEmpty() && urlStr.startsWith(localBaseUrl)) {
            return false
        }

        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showFloatingMenu(anchor: View) {
        val deck = currentDeck ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.menu_back))
        popup.menu.add(0, 2, 1, getString(R.string.menu_reload))
        popup.menu.add(
            0, 3, 2,
            if (server?.catchUpEnabled == true) getString(R.string.menu_catchup_on)
            else getString(R.string.menu_catchup_off)
        )
        popup.menu.add(0, 4, 3, getString(R.string.menu_pin_shortcut))
        popup.menu.add(0, 5, 4, getString(R.string.menu_open_browser))
        popup.menu.add(0, 6, 5, getString(R.string.menu_debug_log))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> finish()
                2 -> binding.webView.reload()
                3 -> {
                    val srv = server ?: return@setOnMenuItemClickListener true
                    srv.catchUpEnabled = !srv.catchUpEnabled
                    val msg = if (srv.catchUpEnabled) {
                        "Catch-Up ON: missing files will auto-download while online."
                    } else {
                        "Catch-Up OFF: strict 100% offline mode."
                    }
                    DebugLogger.log("Viewer", msg)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                4 -> ShortcutHelper.pinDeckShortcut(this, deck)
                5 -> {
                    if (localBaseUrl.isNotEmpty()) {
                        val browserIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("$localBaseUrl/${deck.entryFile}")
                        )
                        startActivity(browserIntent)
                    }
                }
                6 -> startActivity(Intent(this, DebugLogActivity::class.java))
            }
            true
        }
        popup.show()
    }

    private fun hideCustomFullscreenView() {
        customView?.let { binding.fullscreenContainer.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        binding.fullscreenContainer.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        val srv = server
        server = null
        if (srv != null) {
            Thread { srv.stop() }.start()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DECK_ID = "extra_deck_id"
    }
}
