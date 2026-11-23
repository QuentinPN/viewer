<#
.SYNOPSIS
  Downloads the pinned libmpv build into vendor/mpv/ and verifies its SHA-256.
.DESCRIPTION
  Source: shinchiro/mpv-winbuild-cmake GitHub releases.
  The archive hash is pinned below; the script refuses any mismatch. Bump the
  release, file name and both hashes together, then re-run Gate 1.
  Extraction uses 7-Zip (`7z` on PATH, as on GitHub runners) or the `7zip-bin`
  devDependency. The verified archive is cached outside the repository
  (scripts/cached-download.ps1).
#>
$ErrorActionPreference = 'Stop'

$Release = '20261021'
$Asset = 'mpv-dev-x86_64-20261021-git-e471496c56.7z'
$ArchiveSha256 = 'efc719eff3dc5954b4a5efc800632808bcec01cc9034940dd14e5eb7fe9ea633'
$DllSha256 = '04e4d27dc385dc18ddd93fbe69c75e635b864f3c6ec53a9122df54c2f7842f4a'

$root = Resolve-Path "$PSScriptRoot\.."
$dest = Join-Path $root 'vendor\mpv'
$dll = Join-Path $dest 'libmpv-2.dll'

if ((Test-Path $dll) -and (Get-FileHash $dll -Algorithm SHA256).Hash -eq $DllSha256) {
  Write-Output "libmpv déjà présent et vérifié : $dll"
  exit 0
}

. "$PSScriptRoot\cached-download.ps1"
New-Item -ItemType Directory -Force $dest | Out-Null
$archive = Get-VerifiedDownload -Url "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$Release/$Asset" `
  -FileName $Asset -Sha256 $ArchiveSha256

$sevenZip = (Get-Command 7z -ErrorAction SilentlyContinue).Source
if (-not $sevenZip) { $sevenZip = Join-Path $root 'node_modules\7zip-bin\win\x64\7za.exe' }
if (-not (Test-Path $sevenZip)) { throw '7-Zip introuvable : installez 7z ou lancez npm ci.' }
& $sevenZip x -y "-o$dest" $archive | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Extraction échouée ($LASTEXITCODE)" }

$dllHash = (Get-FileHash $dll -Algorithm SHA256).Hash
if ($dllHash -ne $DllSha256) { throw "SHA-256 inattendu pour libmpv-2.dll : $dllHash" }
Write-Output "libmpv installé et vérifié : $dll"
