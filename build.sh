#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

SHERPA_VERSION="1.13.8"
AAR="app/libs/sherpa-onnx-${SHERPA_VERSION}.aar"
MODEL="app/src/main/assets/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
mkdir -p app/libs app/src/main/assets

if [ ! -f "$AAR" ]; then
  echo "==> Downloading sherpa-onnx Android runtime"
  curl -fL --retry 3 \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar" \
    -o "$AAR"
fi

if [ ! -f "$MODEL" ]; then
  echo "==> Downloading speaker embedding model"
  curl -fL --retry 3 \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" \
    -o "$MODEL"
fi

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
