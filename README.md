# Solenya MPV Engine

Solenya MPV Engine is an optional Android plugin that adds MPV playback support to Solenya through a separate installable APK.

The plugin is distributed separately from the main Solenya application. Solenya detects the plugin when it is installed and exposes MPV as an available playback engine next to the built-in players.

## Status

- Package name: `com.solenya.engine.mpv`
- Plugin API version: `1`
- Plugin version: `1.0.0`
- Supported ABIs: `arm64-v8a`, `armeabi-v7a`
- Minimum Android version: Android 8.0 / API 26
- Release APKs:
  - `Solenya-MPV-Engine-v1.0.0-arm64.apk`
  - `Solenya-MPV-Engine-v1.0.0-armeabi-v7a.apk`

## Installation

Install the release APK on the same Android device as Solenya. Once installed, Solenya can detect the plugin and enable MPV playback inside the Solenya interface.

Public APK builds are distributed from this repository's GitHub Releases page.

## What This Plugin Does

This plugin provides MPV playback through an AIDL service bridge used by Solenya. It is not meant to be launched as a standalone media player.

## Native Player Stack

The bundled native libraries are built from the upstream `mpv-android` build system for `arm64-v8a` and `armeabi-v7a`.

Current native build provenance:

- mpv-android: `3018d47277d5b3ca02acdd96466f261c1d23ee08`
- mpv: `b1d23bba5e98a832b312b1b3afa191b0c60ab87c`
- FFmpeg: `n8.0.1` / `894da5ca7d742e4429ffb2af534fcda0103ef593`
- dav1d: `f995e1fbf9379027367a93aafd2b5711ba76f81e`
- libplacebo: `27aa71a97f4daed84916936572fa6a2e1c3eedb7`
- Android NDK: `29.0.14206865`
- Android SDK platform: `35`

See `NATIVE_BUILD.md` and `native/SHA256SUMS.txt` for reproducibility and checksums.

## Release Metadata

`manifest.json` is the machine-readable update file consumed by Solenya. It contains ABI-specific APK URLs, version metadata, ABI support, and SHA-256 checksums.

## License

This repository is distributed under the GPL license text included in `LICENSE` because the APK bundles an MPV-based native playback stack.

See also:

- `NOTICE.md`
- `THIRD_PARTY_NOTICES.md`
- `LICENSE-NOTES.md`
