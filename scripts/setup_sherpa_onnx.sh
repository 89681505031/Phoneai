#!/usr/bin/env bash
set -euo pipefail
VERSION="1.13.8"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}"
DEST="app/libs/sherpa-onnx-${VERSION}.aar"
mkdir -p app/libs
if [[ ! -s "$DEST" ]]; then
  curl -fL --retry 3 "$BASE/sherpa-onnx-${VERSION}.aar" -o "$DEST.tmp"
  mv "$DEST.tmp" "$DEST"
fi
echo "sherpa-onnx AAR ready: $DEST"
