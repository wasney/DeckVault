### DeckVault v1.1.1-beta.2 (Beta Pre-Release)

> **Beta Testing Build:** Official users on `v1.1.0` stay on the stable release unless they opt into **Enable Beta / Pre-release updates** under **Settings**.

#### What's Changed (Play Protect Hardening)
- **Signed Release APK (`assembleRelease`):** Switched GitHub Actions builds from `debug` (`android:debuggable="true"`) to signed `release` builds using the same persistent signing key for clean in-place updates.
- **Removed High-Risk Heuristic Permissions:** Removed `REQUEST_INSTALL_PACKAGES` and `RECEIVE_BOOT_COMPLETED` / boot receiver. DeckVault still checks GitHub Releases on launch and in **Settings**, and hands off APK downloads cleanly to your browser.
- **Tightened Network & WebView Security:** Scoped cleartext HTTP strictly to `127.0.0.1` and `localhost` (`base-config cleartextTrafficPermitted="false"`), disabled `WebView` `allowFileAccess`, and enforced `MIXED_CONTENT_NEVER_ALLOW`.
- **Fixed Release Compilation:** Fixed string escaping in `MainActivity.kt`.
