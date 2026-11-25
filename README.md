# DeckVault

**Offline Interactive Web Deck Saver, Media Optimizer, Local Server & Launcher for Android**

DeckVault is a native Kotlin Android application that downloads interactive HTML5 web decks—including Adobe InDesign (`in5`) presentations with lazy-loaded graphics, clickable flip cards, pop-up infographics, linked one-pager PDFs, and embedded videos—and turns them into permanent, compact, 100% offline resources on your phone or tablet.

---

## Why DeckVault Was Created

Field trainers and sales teams frequently rely on interactive HTML5 web decks inside retail stores, basements, and rural locations where cellular service is unreliable or unavailable. Standard offline tools break down on modern interactive decks for three reasons:

1. **Lazy-loaded assets get skipped:** Export frameworks like Adobe InDesign `in5` hide real images behind placeholders (`blank.gif`) and load graphics/media dynamically via `data-src`, `data-aniurl`, inline `onclick` triggers, CSS `url()`, and JavaScript configs. Tools like `wget` or "Save Page As" miss those assets and produce blank slides.
2. **Android blocks local `file://` JavaScript:** Opening a saved HTML file directly from Android storage fails due to browser CORS and security restrictions.
3. **Web archive recorders bloat storage & still miss slides:** Recording live network traffic into `.wacz` archives duplicates HTTP `206 Partial Content` video range chunks and misses alternate layouts or unclicked slides.

DeckVault solves all three problems directly on Android—no PC or command line required.

---

## How It Works

### 1. Deep Background Asset Crawler (`DeckCrawler` + `DeckDownloadService`)
- Paste a URL (or share a link directly from Chrome/Samsung Internet into DeckVault) and tap **Save Offline**.
- Runs inside an Android Foreground Service with a **sequential multi-deck download queue**, letting you queue multiple decks back-to-back.
- Parses HTML (`src`, `data-src`, `data-aniurl`, `srcset`, `poster`, `onclick`), CSS (`url()`, `@import`), and JavaScript asset paths, downloading each unique file once while filtering out soft-404 HTML error pages.

### 2. Self-Contained Storage Compression (`DeckOptimizer` + `DeckOptimizationService`)
Each deck remains 100% self-contained in its own folder (no fragile cross-deck dependencies when deleting old decks) and can be compressed using three presets (**Lossless**, **Balanced**, **Compact**):
- **Transparent WebP Images:** Converts oversized PNG/JPG assets into WebP (preserving transparency) and serves them seamlessly on original filenames via `LocalDeckServer`.
- **Hardware Video Re-Encoding (Jetpack Media3):** Shrinks desktop-bitrate `.mp4`/`.mov` videos down to crisp mobile 1080p/720p H.264/AAC using hardware `MediaCodec` acceleration.
- **Transparent On-Disk GZIP:** Compresses HTML, CSS, JS, JSON, and SVG files on disk and streams them with `Content-Encoding: gzip`.
- **Safe Copy Mode & Storage Inspector:** Inspect exact storage breakdown per deck (**More → Storage Info**) and test compression on a copy (**Keep original**) before replacing originals.

### 3. On-Demand Local IPv4 Web Server (`LocalDeckServer`)
- Spins up a lightweight loopback HTTP server on `127.0.0.1` only while a deck is open (zero background battery drain).
- Full **HTTP `206 Partial Content`** byte-range streaming support for smooth offline video playback and scrubbing.
- **Hybrid Catch-Up Mode:** Toggle **Catch-Up: ON** from the viewer's floating **Menu** while online to permanently cache any dynamically loaded asset missed during crawling.

### 4. Sharing, Shortcuts & Dual-Channel Auto-Updates
- **1-Tap Home Screen Shortcuts:** Pin any saved deck to your Android home screen as a standalone full-screen icon.
- **Built-In Offline PDF Viewer:** Opens linked one-pager PDFs natively offline via Android's `PdfRenderer`.
- **Quick Share / Wi-Fi Direct Sharing:** Export any deck (original or compressed) into a portable `.deckvault.zip` bundle via **More → Share (Quick Share / ZIP)** and tap **Import** on the receiving device.
- **Official + Beta GitHub Auto-Updater:** Checks [GitHub Releases](https://github.com/wasney/DeckVault/releases) on launch, optionally on device boot, or manually in **Settings**. Stable releases stay the default for all users, while testers can flip **Enable Beta / Pre-release builds** in **Settings** to pull beta builds from the same repository.

---

## Installation

1. Open the **[Releases](https://github.com/wasney/DeckVault/releases)** page on your Android phone or tablet.
2. Download the latest official `DeckVault-vX.Y.Z-debug.apk` (or opt into Beta builds inside **Settings** after installing).
3. Tap the APK to install (allow **Install unknown apps** when prompted).
