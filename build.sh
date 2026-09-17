#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Building Android build image (first run will take a while)"
podman build -t meeting-coach-android-builder -f Containerfile .

echo "==> Building debug APK"
podman run --rm \
  -v "$PWD:/project:Z" \
  -w /project \
  meeting-coach-android-builder \
  gradle --no-daemon :app:assembleDebug

APK="$PWD/app/build/outputs/apk/debug/app-debug.apk"
echo
echo "Built:"
echo "  $APK"
ls -lh "$APK"
