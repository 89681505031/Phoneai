$ErrorActionPreference = "Stop"
$Root = Resolve-Path "$PSScriptRoot\.."
$ThirdParty = Join-Path $Root "third_party"
$Whisper = Join-Path $ThirdParty "whisper.cpp"
$DefaultRef = "d09f61a708f3487afa956ff578e60eae5e7a233c"
$WhisperRef = if ($env:WHISPER_CPP_REF) { $env:WHISPER_CPP_REF } else { $DefaultRef }

New-Item -ItemType Directory -Force -Path $ThirdParty | Out-Null
if (-not (Test-Path (Join-Path $Whisper ".git"))) {
    git clone --filter=blob:none --no-checkout https://github.com/ggml-org/whisper.cpp.git $Whisper
}
git -C $Whisper fetch --depth 1 origin $WhisperRef
git -C $Whisper checkout --detach FETCH_HEAD
python "$Root\scripts\patch_whisper_android.py"
Write-Host "whisper.cpp ready at $WhisperRef"
