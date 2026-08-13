<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Tasirin Download Manager" width="96"><br>
  <b>Tasirin Download Manager — Android</b><br>
  A complete download manager + web remote control, comfortable on TV boxes & phones.
</p>

# Tasirin Download Manager (Android)

[![Build](https://github.com/tasirin1/tasirin-download-manager/actions/workflows/build.yml/badge.svg)](https://github.com/tasirin1/tasirin-download-manager/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-download-manager)](https://github.com/tasirin1/tasirin-download-manager/releases)

<p align="center"><b>&#127760; Language: <a href="README.md">Indonesia</a> &middot; <a href="README.en.md">English</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

**One app for all your file needs on Android:** fast downloads, browser-based management over Wi-Fi, file browsing, a YouTube-style gallery player, and realtime monitoring — great on phones and TV boxes (Android 5.0+ / API 21+).

Built with **Kotlin + Jetpack**, no ads, no account. Open source on GitHub; every update is automatically built into a ready-to-install APK.

> **For AI agents maintaining this repo: read [AGENTS.md](AGENTS.md) first** — it
> contains the structure, architecture, development rules, and build/release flow.
> Agents must read it before changing or maintaining the code.

## Table of contents

- [Features](#features)
- [Download](#download)
- [Automatic APK updates](#automatic-apk-updates)
- [Realtime Remote Web](#realtime-remote-web)
- [YouTube-style video player](#youtube-style-video-player)
- [Storage](#storage)
- [Gallery](#gallery)
- [Settings](#settings)
- [Troubleshooting](#troubleshooting)
- [Build](#build)
- [AI maintenance guide (AGENTS.md)](#ai-maintenance-guide-agentsmd)
- [License](#license)

## Features

| | |
|---|---|
| 🚀 **Full download manager** | multi-segment, Range resume, smart queue, speed limit, auto-retry |
| 📡 **Realtime remote web (SSE)** | control from another device's browser, instant updates without manual refresh |
| 🖥️ **YouTube-style video player** | red seekbar, double-tap ±10s, volume/brightness gestures, auto-next |
| 🗂️ **Remote file manager** | browse, upload, bulk delete, folder ZIP, instant media preview |
| 🖼️ **Device media gallery** | 16:9 thumbnails, real duration, photo/video filters, separate photo & video folders |
| 📶 **Ready for slow networks** | adjustable connect/read timeouts, automatic mirrors, adaptive polling |
| 🔋 **TV box ready** | D-pad/remote support, background server, auto-start on boot |

Download features:

- **Realtime progress**: percentage, size, KB/s–MB/s speed, **stable ETA** (moving average, no jumps) + speed graph
- **Multi-segment** for large files that support Range, parallel downloads with a queue (max configurable)
- **Smart queue**: small files first (optional) + manual priority per download
- Pause, resume (HTTP Range), cancel, delete, retry failed — everything is persisted automatically
- **Foreground service**: downloads continue when the app is closed, automatic retry with backoff, **auto-start on boot**
- **Auto-resume when connectivity returns**: downloads interrupted by lost network resume by themselves (Android 5–6 via broadcast, 7+ via NetworkCallback)
- **Per-download speed limit & priority**, HTTP Basic auth + custom headers (Referer, Cookie, etc.)
- Paste multiple URLs at once, URL history, **Share from other apps** straight into the add dialog
- Optional checksum (MD5/SHA1/SHA256), automatic duplicate names `name (1).ext`
- **HLS / m3u8**: automatic detection and quality (variant) selection before downloading
- **Update monitoring**: download items can be polled periodically — check for new versions at the same URL and download automatically
- **Full auto-sort** after completion: `Videos/`, `Photos/`, `Music/`, `Documents/`, `APK/` (in settings)
- **Smart fallback when Range is rejected**: servers that don't support Range are downloaded in a single pass automatically, no total failure
- **Automatic mirrors** for slow/failed servers; failed URLs are blacklisted to avoid endless retries
- Theme follows the system (auto / light / dark); UI language is English

## Download

The latest APK is always available on **GitHub Releases** — every push to `main` is built and the release is refreshed automatically:

**[⬇️ Download latest APK](https://github.com/tasirin1/tasirin-download-manager/releases/latest)**

Release APKs are **signed with the official release key** (not debug), so Android/Play Protect trusts them more. Install on your phone / TV box (Android 5.0+), and grant Storage permission when asked.

## Automatic APK updates

- The app checks this repo's **releases** itself (`Updater.kt`): the version is
  read from the asset name `tasirin-download-manager-v1.0-<code>.apk`, picking
  the highest code.
- New version found → **Update available** dialog in Settings → download the APK
  to the **Downloads** folder (release signature verified) → **install it
  manually** from a file manager. The app intentionally does **not** request
  "install other apps" (`REQUEST_INSTALL_PACKAGES`) to keep a lower risk
  profile for Play Protect.

## Play Protect warns "harmful to your data"

This is **normal for sideloaded apps** (not from Play Store) that request
"All files access" (`MANAGE_EXTERNAL_STORAGE`) — required by the remote File
Manager and direct-path access. The app also runs a local HTTP server that can
download/upload files, so Play Protect's heuristics rate it "risky for data".
It is **not malware**:

- **VirusTotal scan is 0/75** for all recent releases (report link appears in
  the GitHub Actions build log).
- The APK is signed with the official release key; the signature is verified
  before an update is used.
- Without "All files access", downloads & gallery keep working (via MediaStore);
  only the remote File Manager needs that permission.

When the install warning appears, choose **Install anyway**. The "install other
apps" permission that also triggered warnings has been removed since this
release.

## Realtime Remote Web

The built-in remote server runs **fully in the background** and can **auto-start on boot** — everything is set in **⋮ → Settings**.

1. In Settings, start the server (or let it start automatically on boot)
2. Scan the **QR code**, or open `http://<device-ip>:<port>/` in another device's browser
3. Enter the **PIN** if set

Remote page features:

- **Realtime via SSE**: progress & status come straight from the device without manual refresh; automatic polling fallback when the network blocks streaming
- **Adaptive polling**: 2 seconds when active, 10 seconds when idle — battery friendly
- **Active items pinned to the top** + live total speed in the top bar
- **Mobile-first UI**: "+" FAB, bottom-sheet action menus, sticky filters, skeleton loading, informative empty states
- **Connection indicator**: "updated Xs ago", status dot blinks red when disconnected, auto-refresh when the tab regains focus
- **Upload files & folders from the browser**: 2 MB chunks with retry (an interruption does not restart from zero), drag & drop, automatic duplicate names, confirmation before closing the tab
- **Remote file manager**: browse, create folders, rename, move, bulk delete, **download folders as ZIP**, breadcrumbs, instant media preview
- **YouTube-style remote gallery**: 16:9 thumbnails, real duration badge (cached on device), progressive loading, **All/Photos/Videos filter**
- **Share files via temporary link** (valid 24 hours, no PIN) + QR code
- **Streaming** of completed files (HTTP Range for video/audio) or direct download
- Battery & storage status, port selection, background server + auto-start
- **Auto-lock**: the remote page asks for the PIN again after 10 minutes without activity

### API endpoints (HttpControlServer)

| Endpoint | Purpose |
|---|---|
| `/api/snapshot` | Server & device status (version, port, quick summary) |
| `/api/events` | SSE — realtime events (downloads, logs, gallery) |
| `/api/pin_enabled` | Whether the PIN is enabled |
| `/api/login`, `/api/logout` | PIN session (token) |
| `/api/downloads` | Download list + status |
| `/api/add` | Add download URL |
| `/api/action` | Pause/resume/cancel/delete/priority, etc. |
| `/api/fs`, `/api/fs_action` | File browsing & actions (create/delete/rename/move) |
| `/api/fs_zip` | Download folder as ZIP |
| `/api/upload`, `/api/upload_verify` | 2 MB chunk upload + verification |
| `/api/gallery`, `/api/media`, `/api/media_zip`, `/api/thumb` | Gallery, streaming (Range), batch ZIP download, thumbnails |
| `/api/delete_media` | Delete gallery media |
| `/api/qr`, `/api/share` | QR code, temporary share link (24 hours) |

## YouTube-style video player

- Red seekbar + buffered, **double-tap ±10 seconds**, brightness/volume gestures
- Playback speed 0.5×–2×, resume from last position
- **Video suggestions** below the player, **AUTO (auto-next)** turns on automatically

## Storage

Files are saved to the **Downloads folder** by default. `minSdk 21` is kept (Android 5+ fully supported), `targetSdk 36`. Android 5–10 uses `WRITE_EXTERNAL_STORAGE` + legacy storage (full access); Android 11+ uses `MANAGE_EXTERNAL_STORAGE` ("All files access").

- **Text path input**: type a raw path like `/storage/emulated/0/Download` — the folder is created automatically if missing
- **Extra (mounted) folders**: tap **+** to add another path (e.g. `/sdcard/Movies`) so it shows up in the file manager — great for Total Commander-created folders, SD card folders, etc.
- Choices are saved automatically and persist across restarts

## Gallery

- **Photo & video folders configured separately** in Settings: set a photo gallery folder and a video gallery folder each (e.g. videos only in `/sdcard/Movies/Files`, photos left to scan everything)
- Leave empty to scan all storage; `/sdcard/...` paths are automatically recognized as `/storage/emulated/0/...`
- Shows video duration, fast thumbnails, direct playback, file deletion

## Settings

Menu **⋮ → Settings**:

- **Downloads**: auto-resume, auto-start on boot, concurrent downloads (1–5), segment count, speed limit, retries (0–5), small files first, **connect (5–60s) & read (10–120s) timeouts** for slow networks/WISPs
- **Server**: port, PIN, QR code, background + auto-start on boot
- **Storage**: destination folder + extra folders
- **Gallery**: separate photo & video folders
- **Cleanup**: delete logs/completed downloads
- **Realtime Server Log**: activity log of the whole system (HTTP requests, downloads, gallery, errors) — searchable, highlighted, and **exportable to a TXT file** for easy bug reports

Note: some vendors (MIUI, etc.) enforce aggressive battery limits — enable *auto-start* in the system settings so the service is not killed.

## Troubleshooting

**Remote won't open from another device**
- Make sure the server is **running** (status in the app), devices are on the **same network**,
  and the URL uses the local IP. Try scanning the QR from Settings.

**PIN keeps being requested / auto-lock**
- Auto-lock locks the remote page after 10 minutes without activity — enter the
  PIN again. Forgot the PIN → disable and re-enable the PIN in Settings (the PIN is
  stored locally on the device, not on a server).

**Download fails with "Server does not support Range"**
- It is downloaded in a single pass automatically (single-stream) — no total failure.
  Servers without resume support can still be downloaded.

**Downloads stop when the phone sleeps**
- Enable **auto-start/background** in the system settings (MIUI vendors, etc.
  restrict services). In the app make sure the *foreground service* is active and
  battery-optimization exemption is granted.

**Connection to GitHub fails (APK update/checksum)**
- Android 5/6 needs legacy TLS trust anchors (bundled). Check the connection,
  try again from another device; the update can be safely retried.

**Empty gallery on Android 6**
- Handled: the MediaStore projection is conditional (the `RELATIVE_PATH` column
  only exists on API 29+). Make sure the gallery folder is correct or clear it to
  scan everything.

## Build

### Official (GitHub Actions) — the only release source

The `.github/workflows/build.yml` workflow runs automatically on every push to `main`
(or manually via **Actions → Build APK → Run workflow**). The result becomes a
**new release** (`v1.0`, APK `tasirin-download-manager-v1.0-<code>.apk`).

For a signed release, fill in the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` secrets in the repo settings — the signed release APK
is uploaded to the release, not the debug APK.

> **⚠️ Back up the keystore & passwords forever.** The release key signs all
> releases — if it is lost, devices cannot update old APKs without uninstalling,
> and a new key makes Play Protect suspicious. Store it safely (password manager),
> never commit it to the repo (`.gitignore` already covers `*.jks` & `keystore.b64`).

### Local (debug only)

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Local builds are **for quick debugging only** — never the official release
source. All changes go through commits + push, then CI builds.

**Requirements**: Android 5.0+ (minSdk 21), Java 17 + Android SDK.

## AI maintenance guide (AGENTS.md)

The repository maintenance guide that used to live here (structure, architecture,
development rules, how to trigger builds & releases, CI pipeline, secrets, files
touched per feature, build verification, and the targetSdk 36 roadmap) has been
moved to **[AGENTS.md](AGENTS.md)**.

> **Required for AI**: read `AGENTS.md` before changing, fixing, or maintaining
> this repository — all maintenance instructions live there.

## License

MIT — see [LICENSE](LICENSE).
