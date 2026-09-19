<p align="center">
  <img src="docs/icon.png" width="160" height="160" alt="Airoid" />
</p>

<p align="center">
  <a href="README.md">한국어</a> · <a href="README.en.md">English</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh.md">中文</a>
</p>

# Airoid

An app that turns an Android device into an AirPlay receiver. Wirelessly mirror
the screen of a Mac or iPhone onto your Android device as a secondary display,
and play AirPlay audio.

## Features

- **Screen mirroring** — H.264/H.265 hardware decoding, low-latency pipeline
- **Audio reception** — AAC (hardware), ALAC (software fallback when no hardware)
- **Transition animation** — the device frame (ring) grows/shrinks on connect/end,
  with a video fade
- **Quick Settings tile** — shows the mirroring state (ACTIVE/INACTIVE); tap to
  launch the app
- **Multilingual UI** — 한국어/English/日本語/中文(简体), with per-app language support
- **Pairing code** — devices are identified by a literary title in your language
  (e.g. `Airoid Moby Dick`)

## Install

Requirements: **Android 8.0 or later**, ARM64 device

1. Download the latest `app-release.apk` from one of the release pages below.
   - GitHub: <https://github.com/hanana-kick/Airoid/releases/latest>
2. Open the downloaded APK. If you see an "install unknown apps" warning, allow
   install permission for the browser/file manager you downloaded it with.
3. Once installed, launch the app.

To update later, install the new APK over the old one the same way (your data is
kept).

## Usage

1. Launching the app shows a pairing code on the standby screen (e.g.
   `Airoid Moby Dick`).
2. Select this device from the Mac's AirPlay menu to start mirroring.
3. While mirroring, swipe up to open the "End Mirroring" panel.
4. (Optional) Add the "Airoid" tile in Quick Settings edit mode to check the state
   and launch the app with a tap.

## License

Third-party components (UxPlay, FFmpeg, libplist, openssl, oboe) are governed by
their respective licenses.
