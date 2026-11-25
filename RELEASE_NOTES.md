### DeckVault v1.1.0

#### What's New
- **Offline Storage Compression (Self-Contained per Deck):**
  - **Smart WebP Image Conversion:** Converts PNG and JPG graphics to WebP at 100% original pixel dimensions (preserving transparency and exact `in5` layout alignment) and serves them seamlessly via the local server.
  - **Hardware Video Compression:** Uses Android Media3 hardware H.264/AAC transcoding to shrink oversized `.mp4`/`.mov` videos down to crisp mobile-friendly 1080p (**Balanced**) or 720p (**Compact**) bitrates while keeping smooth offline scrubbing.
  - **Transparent On-Disk GZIP:** Compresses HTML, CSS, JS, JSON, and SVG files on disk and streams them directly with `Content-Encoding: gzip`.
  - **Safe Copy Mode & Storage Inspector:** Check per-deck storage usage (**More → Storage Info**) or compress decks via **More → Optimize / Compress…** (with an option to keep the original as a backup copy) or set an automatic default preset in **Settings**.
- **Official & Beta Release Channels:** Built-in GitHub updater defaults to official releases while letting testers opt into **Enable Beta / Pre-release updates** under **Settings**.
