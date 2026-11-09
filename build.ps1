param(
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$PlatformJar,
    [Parameter(Mandatory)][string]$BuildTools,
    [Parameter(Mandatory)][string]$Zxing,
    [Parameter(Mandatory)][string]$KeyStore,
    [Parameter(Mandatory)][string]$KeyAlias,
    [Parameter(Mandatory)][string]$Apk
)

$ErrorActionPreference = 'Stop'
$Jdk = (Resolve-Path -LiteralPath $Jdk).Path
$PlatformJar = (Resolve-Path -LiteralPath $PlatformJar).Path
$BuildTools = (Resolve-Path -LiteralPath $BuildTools).Path
$Zxing = (Resolve-Path -LiteralPath $Zxing).Path
$KeyStore = (Resolve-Path -LiteralPath $KeyStore).Path
$Apk = [IO.Path]::GetFullPath($Apk)
$env:JAVA_HOME = $Jdk
$build = Join-Path $PSScriptRoot "build\$([Guid]::NewGuid().ToString('N'))"
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
New-Item -ItemType Directory -Force $classes, $dex | Out-Null

function Run($Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable exited with code $LASTEXITCODE" }
}

$aapt = Join-Path $BuildTools 'aapt2.exe'
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
$resource = Join-Path $build 'resources.zip'
$unsigned = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
Run $aapt @('compile', '--dir', "$PSScriptRoot\res", '-o', $resource)
Run $aapt @('link', '-I', $PlatformJar, '--manifest', "$PSScriptRoot\AndroidManifest.xml",
    '-o', $unsigned, $resource)
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot\src" -Recurse -File -Filter '*.java' |
    ForEach-Object FullName)
Run $javac (@('-encoding', 'UTF-8', '--release', '8', '-cp', "$PlatformJar;$Zxing", '-d', $classes) + $sources)
$classesJar = Join-Path $build 'classes.jar'
Run (Join-Path $Jdk 'bin\jar.exe') @('cf', $classesJar, '-C', $classes, '.')
Run $java @('-cp', "$BuildTools\lib\d8.jar", 'com.android.tools.r8.D8', '--release',
    '--min-api', '24', '--lib', $PlatformJar, '--output', $dex, $classesJar, $Zxing)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    foreach ($file in Get-ChildItem -LiteralPath $dex -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $file.Name,
            [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
    }
    foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'LICENSE-*.txt') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $file.Name) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Run (Join-Path $BuildTools 'zipalign.exe') @('-f', '4', $unsigned, $aligned)
$signer = Join-Path $BuildTools 'lib\apksigner.jar'
Run $java @('-jar', $signer, 'sign', '--ks', $KeyStore, '--ks-key-alias', $KeyAlias,
    '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
    '--out', $Apk, $aligned)
Run $java @('-jar', $signer, 'verify', '--verbose', $Apk)
