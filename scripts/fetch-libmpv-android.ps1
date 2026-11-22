<#
.SYNOPSIS
  Downloads the pinned mpv-android release and extracts its arm64-v8a native
  libraries into android-tv/app/src/main/jniLibs/ (ignored by git).
.DESCRIPTION
  Source: mpv-android/mpv-android GitHub releases. The APK hash
  is pinned below; the script refuses any mismatch. Bump the tag, the hashes
  and android-tv/app/src/main/java/is/xyz/mpv/MPVLib.kt together, then re-run
  Gate TV-1.
#>
$ErrorActionPreference = 'Stop'

$Release = '2026-09-17'
$Asset = 'app-default-arm64-v8a-release.apk'
$ApkSha256 = '0B0E0A534DD6B8D2B117F06952E0600064AD97702E48BFF02543E3E32107803D'
$LibmpvSha256 = '03D2D02DB0451CEBF4DA2648188DF7B2502005C6409FAD1841B1D2B91CA8A814'
$Abi = 'arm64-v8a'

$root = Resolve-Path "$PSScriptRoot\.."
$dest = Join-Path $root "android-tv\app\src\main\jniLibs\$Abi"
$libmpv = Join-Path $dest 'libmpv.so'

if ((Test-Path $libmpv) -and (Get-FileHash $libmpv -Algorithm SHA256).Hash -eq $LibmpvSha256) {
  Write-Output "libmpv Android déjà présent et vérifié : $libmpv"
  exit 0
}

$dl = Join-Path $root 'vendor\_dl'
New-Item -ItemType Directory -Force $dl, $dest | Out-Null
$apk = Join-Path $dl "mpv-android-$Release-$Asset"
$url = "https://github.com/mpv-android/mpv-android/releases/download/$Release/$Asset"
Write-Output "Téléchargement $url"
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $apk

$actual = (Get-FileHash $apk -Algorithm SHA256).Hash
if ($actual -ne $ApkSha256) {
  Remove-Item $apk -Force
  throw "SHA-256 inattendu pour $Asset : $actual"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apk)
try {
  foreach ($entry in $zip.Entries) {
    if ($entry.FullName -like "lib/$Abi/*.so") {
      [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $dest $entry.Name), $true)
    }
  }
} finally {
  $zip.Dispose()
}

$libHash = (Get-FileHash $libmpv -Algorithm SHA256).Hash
if ($libHash -ne $LibmpvSha256) { throw "SHA-256 inattendu pour libmpv.so : $libHash" }
Write-Output "libmpv Android installé et vérifié : $dest"
