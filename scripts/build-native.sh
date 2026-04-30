#!/usr/bin/env bash
set -euo pipefail

BUILD_ROOT="${BUILD_ROOT:-$HOME/solenya-mpv-build}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
MPV_ANDROID_COMMIT="3018d47277d5b3ca02acdd96466f261c1d23ee08"

mkdir -p "$BUILD_ROOT"
cd "$BUILD_ROOT"

if [ ! -d mpv-android ]; then
  git clone https://github.com/mpv-android/mpv-android.git
fi

cd mpv-android
git fetch --tags origin
git checkout "$MPV_ANDROID_COMMIT"

cd buildscripts
mkdir -p sdk
ln -sfn "$ANDROID_SDK_ROOT" sdk/android-sdk-linux

IN_CI=1 ./download.sh

for arch in arm64 armv7l; do
  IN_CI=1 ./buildall.sh --arch "$arch" mpv
  IN_CI=1 DONT_BUILD_RELEASE=1 ./buildall.sh --arch "$arch" mpv-android
done

echo "Native libraries:"
echo "$BUILD_ROOT/mpv-android/app/src/main/libs/arm64-v8a"
echo "$BUILD_ROOT/mpv-android/app/src/main/libs/armeabi-v7a"
