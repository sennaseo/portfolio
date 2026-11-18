#!/bin/bash
# Android SDK(플랫폼 35, 빌드 도구 35.0.0)를 설치한다. 이미 있으면 건너뛴다.
# 사용: ANDROID_HOME=/opt/android-sdk bash scripts/setup-android-sdk.sh
set -e
SDK="${ANDROID_HOME:-/opt/android-sdk}"
REPO=https://dl.google.com/android/repository
TMP="$(mktemp -d)"
mkdir -p "$SDK/platforms" "$SDK/build-tools"
if [ ! -d "$SDK/platforms/android-35" ]; then
  curl -fsSL -o "$TMP/p.zip" "$REPO/platform-35_r02.zip"
  unzip -q "$TMP/p.zip" -d "$SDK/platforms"
fi
if [ ! -d "$SDK/build-tools/35.0.0" ]; then
  curl -fsSL -o "$TMP/b.zip" "$REPO/build-tools_r35_linux.zip"
  unzip -q "$TMP/b.zip" -d "$TMP/bt"
  mv "$TMP/bt/android-15" "$SDK/build-tools/35.0.0"
fi
if [ ! -d "$SDK/platform-tools" ]; then
  curl -fsSL -o "$TMP/t.zip" "$REPO/platform-tools-latest-linux.zip"
  unzip -q "$TMP/t.zip" -d "$SDK"
fi
rm -rf "$TMP"
echo "Android SDK ready: $SDK"
