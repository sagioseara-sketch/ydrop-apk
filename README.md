# YDROP Android APK

Personal YouTube Downloader — Full offline APK for Android.

---

## How it works
- Opens a WebView with the YDROP interface
- Runs a local HTTP server (NanoHTTPD) inside the app
- Downloads yt-dlp ARM binary on first launch (~10MB, one time only)
- Uses ffmpeg-kit for audio/video merging (built into the APK)
- Files saved to: Downloads/YTDownloader/

---

## BUILD METHOD 1 — GitHub Actions (Easiest, no setup needed)

1. Create a new GitHub repo (e.g. `ydrop-apk`)
2. Push this entire folder to that repo
3. Go to your repo on GitHub → Actions tab
4. Click "Build YDROP APK" → "Run workflow"
5. Wait ~5 minutes
6. Download the APK from the Releases section
7. Install on your phone (enable "Install from unknown sources")

---

## BUILD METHOD 2 — Android Studio on PC

1. Install Android Studio: https://developer.android.com/studio
2. Open this folder as a project in Android Studio
3. Wait for Gradle sync to finish
4. Click Build → Build Bundle(s)/APK(s) → Build APK(s)
5. APK will be at: `app/build/outputs/apk/debug/app-debug.apk`
6. Transfer to phone and install

---

## INSTALL ON PHONE

1. Transfer the APK to your phone (WhatsApp yourself, Google Drive, USB)
2. Open it on your phone
3. If prompted "Install from unknown sources" → Allow
4. Install → Open

### First launch:
- Allow storage permission when asked
- The app will download yt-dlp automatically (~10MB) — wait 30 seconds
- Then paste any YouTube URL and download!

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "yt-dlp download failed" | Check your internet connection on first launch |
| "Download failed" | yt-dlp may be outdated — clear app data to re-download |
| Files not showing in gallery | Open Files app → Downloads → YTDownloader |
| App crashes on open | Make sure you're on Android 8.0 (API 26) or higher |
