#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
THIRD_PARTY="$ROOT/third_party"
WHISPER_DIR="$THIRD_PARTY/whisper.cpp"
WHISPER_REF="${WHISPER_CPP_REF:-d09f61a708f3487afa956ff578e60eae5e7a233c}"

mkdir -p "$THIRD_PARTY"
if [ ! -d "$WHISPER_DIR/.git" ]; then
  git clone --filter=blob:none --no-checkout https://github.com/ggml-org/whisper.cpp.git "$WHISPER_DIR"
fi

git -C "$WHISPER_DIR" fetch --depth 1 origin "$WHISPER_REF"
git -C "$WHISPER_DIR" checkout --detach FETCH_HEAD
python3 "$ROOT/scripts/patch_whisper_android.py"
echo "whisper.cpp ready at $WHISPER_REF"
