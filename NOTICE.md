# Notices

This repository contains a separate Android plugin that allows Solenya to use MPV through an AIDL service.

## Separation From Solenya

The MPV engine must remain outside the proprietary Solenya APK. The main app should only contain the external player bridge, plugin detection, and installer UI.

## Bundled Native Dependency

The plugin bundles native libraries under:

```text
native/arm64-v8a/
```

These libraries are built from upstream `mpv-android` sources and are packaged only inside this plugin project.

## Upstream Source

Native build source:

```text
https://github.com/mpv-android/mpv-android
```

Exact commits and build instructions are documented in `NATIVE_BUILD.md`.

## Distribution Reminder

If this repository publicly distributes an APK containing MPV/native components, keep the corresponding source/provenance/build instructions available in this repository or in the associated GitHub release.
