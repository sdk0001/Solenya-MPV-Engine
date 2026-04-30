# Development Notes

This file is intended for developers building the plugin from source.

## Build Debug

From the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Build a Signed Release

1. Copy `signing.properties.example` to `signing.properties`.
2. Fill the keystore path, passwords, and alias.
3. Run:

```powershell
.\gradlew.bat :app:assembleRelease
```

Signed release APK output:

```text
app\build\outputs\apk\release\app-release.apk
```

`signing.properties` and keystore files must never be committed.

## Manual Install for Testing

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

## Native Libraries

Native libraries are loaded from:

```text
native\arm64-v8a\
```

The app module includes this directory through `jniLibs.srcDir("../native")`.

To rebuild the native MPV stack, see `NATIVE_BUILD.md`.

## Release Metadata

`manifest.json` is used by Solenya to check plugin availability, version, download URL, supported ABI, and APK SHA-256 hash.

When publishing a new release:

1. Build the signed release APK.
2. Copy it to the GitHub release assets using the filename in `manifest.json`.
3. Update `versionName`, `versionCode`, `apkUrl`, `apkFileName`, `sha256`, and `updatedAt` in `manifest.json`.
4. Update `native/SHA256SUMS.txt` if native libraries or the APK changed.
