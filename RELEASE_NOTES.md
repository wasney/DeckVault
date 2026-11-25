### DeckVault v1.1.0-beta.3 (Beta Pre-Release)

#### What's Fixed
- **Preserved Exact Image Pixel Dimensions (Zero Layout Distortion):** Removed image pixel downscaling (`2048px`/`1440px` caps) during WebP compression. Adobe InDesign `in5` decks size background images, pop-up infographics, and overlays using each image's intrinsic pixel dimensions—keeping 100% original `width × height` eliminates shrunk backgrounds and misaligned pop-up overlays while still shrinking PNG/JPG files dramatically via WebP encoding.
- **Expanded Image Optimization Coverage:** Lowered the minimum image compression threshold from `8 KB` to `2 KB` so smaller `in5` text and UI PNG assets also benefit from WebP savings.
