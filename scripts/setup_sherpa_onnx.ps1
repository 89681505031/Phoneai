$ErrorActionPreference = "Stop"
$version = "1.13.8"
$dest = "app/libs/sherpa-onnx-$version.aar"
New-Item -ItemType Directory -Force -Path "app/libs" | Out-Null
if (-not (Test-Path $dest)) {
  $url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
  Invoke-WebRequest -Uri $url -OutFile "$dest.tmp"
  Move-Item -Force "$dest.tmp" $dest
}
Write-Host "sherpa-onnx AAR ready: $dest"
