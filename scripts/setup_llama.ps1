$ErrorActionPreference = "Stop"
$Root = Resolve-Path "$PSScriptRoot\.."
$ThirdParty = Join-Path $Root "third_party"
$Llama = Join-Path $ThirdParty "llama.cpp"
$DefaultRef = "a97cce86a8addeb9f40cba7a261c94b1f0c576cb"
$LlamaRef = if ($env:LLAMA_CPP_REF) { $env:LLAMA_CPP_REF } else { $DefaultRef }

New-Item -ItemType Directory -Force -Path $ThirdParty | Out-Null

if (-not (Test-Path (Join-Path $Llama ".git"))) {
    git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git $Llama
}

git -C $Llama fetch --depth 1 origin $LlamaRef
git -C $Llama checkout --detach FETCH_HEAD
Write-Host "llama.cpp ready at $LlamaRef"
python "$Root\scripts\patch_llama_android.py"
