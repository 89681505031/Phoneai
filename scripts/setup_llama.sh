#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
THIRD_PARTY="$ROOT/third_party"
LLAMA_DIR="$THIRD_PARTY/llama.cpp"
LLAMA_REF="${LLAMA_CPP_REF:-a97cce86a8addeb9f40cba7a261c94b1f0c576cb}"

mkdir -p "$THIRD_PARTY"

if [ ! -d "$LLAMA_DIR/.git" ]; then
  git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
fi

git -C "$LLAMA_DIR" fetch --depth 1 origin "$LLAMA_REF"
git -C "$LLAMA_DIR" checkout --detach FETCH_HEAD

echo "llama.cpp ready at $LLAMA_REF"
python3 "$ROOT/scripts/patch_llama_android.py"
