param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$BuildToolsVersion = "36.1.0",
    [string]$KeystorePath,
    [string]$KeyAlias = "androiddebugkey"
)

$ErrorActionPreference = "Stop"
$sourceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path $sourceRoot "build"
$toolsRoot = Join-Path $SdkRoot "build-tools\$BuildToolsVersion"
$androidJar = Join-Path $SdkRoot "platforms\android-36\android.jar"
$aapt2 = Join-Path $toolsRoot "aapt2.exe"
$aapt = Join-Path $toolsRoot "aapt.exe"
$d8 = Join-Path $toolsRoot "d8.bat"
$zipalign = Join-Path $toolsRoot "zipalign.exe"
$apksigner = Join-Path $toolsRoot "apksigner.bat"
$javac = (Get-Command javac.exe).Source
$jar = Join-Path (Split-Path -Parent $javac) "jar.exe"
$required = @($androidJar, $aapt2, $aapt, $d8, $zipalign, $apksigner, $javac, $jar)
if ($KeystorePath) { $required += $KeystorePath }
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Falta una dependencia de compilación: $path"
    }
}

if (Test-Path -LiteralPath $buildRoot) {
    $resolvedBuild = (Resolve-Path -LiteralPath $buildRoot).Path
    $expectedBuild = [IO.Path]::GetFullPath((Join-Path $sourceRoot "build"))
    if ($resolvedBuild -ne $expectedBuild -or
        (Get-Item -LiteralPath $buildRoot).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw "La carpeta build no coincide con el directorio local esperado"
    }
    Remove-Item -LiteralPath $buildRoot -Recurse -Force
}
$compiledResources = Join-Path $buildRoot "compiled-resources.zip"
$generatedRoot = Join-Path $buildRoot "generated"
$classesRoot = Join-Path $buildRoot "classes"
$dexRoot = Join-Path $buildRoot "dex"
New-Item -ItemType Directory -Force -Path $buildRoot, $generatedRoot, $classesRoot,
    $dexRoot | Out-Null

& $aapt2 compile --dir (Join-Path $sourceRoot "res") -o $compiledResources
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile falló" }

$resourcesApk = Join-Path $buildRoot "resources.apk"
& $aapt2 link -I $androidJar --manifest (Join-Path $sourceRoot "AndroidManifest.xml") `
    --java $generatedRoot --min-sdk-version 24 --target-sdk-version 25 `
    --version-code 17 --version-name 1.16 -o $resourcesApk $compiledResources
if ($LASTEXITCODE -ne 0) { throw "aapt2 link falló" }

$javaFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $sourceRoot "src") -Recurse -Filter "*.java"
    Get-ChildItem -LiteralPath $generatedRoot -Recurse -Filter "*.java"
) | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar `
    -d $classesRoot $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac falló" }

$classesJar = Join-Path $buildRoot "classes.jar"
Push-Location $classesRoot
try {
    & $jar cf $classesJar .
    if ($LASTEXITCODE -ne 0) { throw "jar falló" }
} finally {
    Pop-Location
}

& $d8 --lib $androidJar --min-api 24 --output $dexRoot $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 falló" }

$unsignedApk = Join-Path $buildRoot "wifi-autoconnect-unsigned.apk"
Copy-Item -LiteralPath $resourcesApk -Destination $unsignedApk
Push-Location $dexRoot
try {
    & $aapt add $unsignedApk "classes.dex"
    if ($LASTEXITCODE -ne 0) { throw "No se pudo agregar classes.dex" }
} finally {
    Pop-Location
}

$alignedApk = Join-Path $buildRoot "wifi-autoconnect-aligned.apk"
& $zipalign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign falló" }

if ($KeystorePath) {
    $outputApk = Join-Path $buildRoot "wifi-autoconnect-v1.16.apk"
    $signArguments = @("sign", "--ks", $KeystorePath, "--ks-key-alias", $KeyAlias)
    # apksigner pide la contraseña si no se proporciona mediante el entorno.
    if ($env:V480_KEYSTORE_PASSWORD) {
        $signArguments += @("--ks-pass", "env:V480_KEYSTORE_PASSWORD")
    }
    $signArguments += @("--out", $outputApk, $alignedApk)
    & $apksigner @signArguments
    if ($LASTEXITCODE -ne 0) { throw "apksigner falló" }
    & $apksigner verify $outputApk
    if ($LASTEXITCODE -ne 0) { throw "La verificación de firma falló" }
} else {
    $outputApk = Join-Path $buildRoot "wifi-autoconnect-v1.16-unsigned.apk"
    Copy-Item -LiteralPath $alignedApk -Destination $outputApk
    Write-Host "APK sin firmar: para instalar, recompilar con -KeystorePath y -KeyAlias."
}

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputApk).Hash.ToLowerInvariant()
Write-Host "APK: $outputApk"
Write-Host "SHA-256: $hash"
