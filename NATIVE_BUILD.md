# Native Build

This release uses fresh `arm64-v8a` and `armeabi-v7a` native stacks built with the upstream `mpv-android` build system.

## Build Environment

- Host: WSL2 Ubuntu
- Android SDK: `/home/sadik/Android/Sdk`
- Android SDK platform: `35`
- Android build tools: `35.0.0`
- Android NDK: `29.0.14206865`
- Meson: `1.11.1`
- Java: OpenJDK 17

## Source

```text
Repository: https://github.com/mpv-android/mpv-android
Commit:     3018d47277d5b3ca02acdd96466f261c1d23ee08
```

Native dependency commits used by the build:

```text
mpv:        b1d23bba5e98a832b312b1b3afa191b0c60ab87c
mpv tag:    v0.41.0-568-gb1d23bba5e
FFmpeg:     894da5ca7d742e4429ffb2af534fcda0103ef593
FFmpeg tag: n8.0.1
dav1d:      f995e1fbf9379027367a93aafd2b5711ba76f81e
libplacebo: 27aa71a97f4daed84916936572fa6a2e1c3eedb7
```

## Rebuild Commands

```bash
sudo apt update
sudo apt install -y build-essential git curl wget unzip zip python3 python3-pip cmake ninja-build meson pkg-config yasm nasm gperf bison flex autoconf automake libtool gettext texinfo openjdk-17-jdk
sudo python3 -m pip install --break-system-packages --upgrade meson

mkdir -p /home/sadik/solenya-mpv-build
cd /home/sadik/solenya-mpv-build
git clone https://github.com/mpv-android/mpv-android.git
cd mpv-android
git checkout 3018d47277d5b3ca02acdd96466f261c1d23ee08

cd buildscripts
mkdir -p sdk
ln -sfn /home/sadik/Android/Sdk sdk/android-sdk-linux

IN_CI=1 ./download.sh
for arch in arm64 armv7l; do
  IN_CI=1 ./buildall.sh --arch "$arch" mpv
  IN_CI=1 DONT_BUILD_RELEASE=1 ./buildall.sh --arch "$arch" mpv-android
done
```

Native library output:

```text
/home/sadik/solenya-mpv-build/mpv-android/app/src/main/libs/arm64-v8a/
/home/sadik/solenya-mpv-build/mpv-android/app/src/main/libs/armeabi-v7a/
```

The Solenya plugin copies those `.so` files to:

```text
native/arm64-v8a/
native/armeabi-v7a/
```

## Release APK

Signed release output is copied to:

```text
release/Solenya-MPV-Engine-v1.0.0-arm64.apk
release/Solenya-MPV-Engine-v1.0.0-armeabi-v7a.apk
```

APK SHA-256:

```text
arm64-v8a:
E19406BA3C13DEE7C49733FC73DF664833DE03B8334465F90868F769311B1E4E

armeabi-v7a:
13DD1BC2FCE1A450936EE340934682FBACAFD73F17D8DBFBAD442ECCCADB9353
```

The player fixes are Kotlin/AIDL changes and reuse these exact native libraries; rebuilding MPV or `libplayer.so` is not required. Package the Android APKs with `scripts/package-release.ps1 -VersionName 1.0.0`, then record their APK checksums in `manifest.json` when publishing the release.
