$ErrorActionPreference = "Stop"

$sourceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputPath = Join-Path $sourceRoot "v480-charge-control"
$sourcePath = Join-Path $sourceRoot "charge-control.c"

$zig = if ($env:ZIG_EXE) {
    $env:ZIG_EXE
} else {
    (Get-Command zig -ErrorAction Stop).Source
}

& $zig cc -target arm-linux-musleabi -mcpu=cortex_a7 -Os `
    -fPIE -pie -static -s `
    -o $outputPath $sourcePath
if ($LASTEXITCODE -ne 0) { throw "zig cc fallo" }

Get-FileHash -Algorithm SHA256 -LiteralPath $outputPath
