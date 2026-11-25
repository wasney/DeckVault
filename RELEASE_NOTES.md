### DeckVault v1.1.1

#### What's Improved
- **Signed Release APK Builds:** Switched builds from `debug` (`android:debuggable="true"`) to signed `release` APKs (`DeckVault-v1.1.1-release.apk`) using the same persistent signing key for seamless in-place updates without losing saved decks.
- **Play Protect & Security Hardening:** Removed `REQUEST_INSTALL_PACKAGES` and boot-receiver permissions, scoped cleartext HTTP strictly to `127.0.0.1` and `localhost`, and tightened `WebView` file-access and mixed-content policies.
- **Browser Update Handoff:** DeckVault checks GitHub Releases on launch and in **Settings** and opens direct APK downloads cleanly in your browser.
