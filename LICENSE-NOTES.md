# License Notes

This project is a separate open-source Android plugin for Solenya.

## Repository License

The repository includes the GNU GPL v2 license text in `LICENSE` because the plugin bundles and exposes an MPV-based native playback stack.

## Android Bridge Code

The local `is.xyz.mpv` wrapper is based on the upstream `mpv-android` project:

```text
https://github.com/mpv-android/mpv-android
```

The upstream Android application code is MIT-licensed. Keep its copyright notice in third-party notices.

## Native MPV Stack

The native libraries are produced by the upstream `mpv-android` build scripts and include MPV, FFmpeg, libplacebo, dav1d, and their dependency chain.

When publishing APKs, keep the exact source commits, build instructions, and checksums available so recipients can identify and rebuild the corresponding native stack.
