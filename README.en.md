# Tasirin Download Manager (Android)

[![Build](https://github.com/tasirin1/tasirin-download-manager/actions/workflows/build.yml/badge.svg)](https://github.com/tasirin1/tasirin-download-manager/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-download-manager)](https://github.com/tasirin1/tasirin-download-manager/releases)

A download manager for Android with realtime web control, a remote file manager, and a video gallery. Built for phones and TV boxes, without ads or accounts.

**Language:** [Indonesian](README.md) · [English](README.en.md) · [Changelog](CHANGELOG.md)

## Features

- Multi-segment transfers, HTTP Range resume, queues, automatic retries, speed limits, and mirrors.
- Realtime remote web using SSE with an adaptive polling fallback.
- Browser uploads that use resumable 2 MB chunks.
- File manager: browse, upload, create folders, rename, move, delete, and download ZIP archives.
- Video gallery with thumbnails, durations, resume playback, suggestions, and D-pad-friendly controls.
- Media streaming with Range support and temporary share links.
- Runs on Android 5.0+ with a foreground service and optional auto-start.

## Download

1. Open the [latest release page](https://github.com/tasirin1/tasirin-download-manager/releases/latest).
2. Download the `tasirin-download-manager-v1.0-<code>.apk` asset.
3. Install the app and grant the requested storage permissions.

Releases are built by GitHub Actions and signed with the official release key. In-app updates only download the APK; installation remains manual.

## Remote Web

1. Open **Settings → Remote (HTTP)**.
2. Start the server, then scan the QR code or open `http://<device-ip>:<port>/`.
3. Enter the PIN when remote PIN protection is enabled.

The server is intended for local networks. Use a PIN on shared devices or unsecured Wi-Fi.

## Security & Privacy

- No account is required and no data is sent to a developer server.
- Remote sessions use a random cookie; the PIN is stored as a PBKDF2 hash.
- Server paths are confined to approved roots, uploads have size limits, and stream tokens are signed and expiring.
- CI runs unit tests, lint, CodeQL, Gitleaks, internal static analysis, and APK signature verification.
- Play Protect may warn about sideloaded apps that request all-files access because its risk model is conservative. Review CI builds before installing an APK.

## Build

Official releases are built only through GitHub Actions:

```bash
# Pushing to main runs tests, lint, signed APK build, and release publishing.
```

For a local debug build:

```bash
./gradlew assembleDebug
```

Run static checks before committing:

```bash
python3 scripts/security_audit.py --self-test
python3 scripts/security_audit.py
python3 scripts/prepare_remote.py --check
node scripts/upload_smoke_test.js
```

## License

MIT — see [LICENSE](LICENSE).
