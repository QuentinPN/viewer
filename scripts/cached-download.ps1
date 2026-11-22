<#
.SYNOPSIS
  Get-VerifiedDownload: returns the path of a pinned file kept in a cache
  outside the repository (`%LOCALAPPDATA%\Viewer-cache\mpv`, or
  `VIEWER_LIBMPV_CACHE`), so a fresh checkout, like each release run on the
  Windows runner, does not download it again. Its SHA-256 is checked every
  time; a mismatch is deleted and refused.
  Dot-source it: `. "$PSScriptRoot\cached-download.ps1"`.
#>

function Get-VerifiedDownload {
  param(
    [Parameter(Mandatory)] [string] $Url,
    [Parameter(Mandatory)] [string] $FileName,
    [Parameter(Mandatory)] [string] $Sha256
  )
  $cache = $env:VIEWER_LIBMPV_CACHE
  if (-not $cache) { $cache = Join-Path $env:LOCALAPPDATA 'Viewer-cache\mpv' }
  New-Item -ItemType Directory -Force $cache | Out-Null
  $file = Join-Path $cache $FileName
  if ((Test-Path $file) -and (Get-FileHash $file -Algorithm SHA256).Hash -eq $Sha256) {
    Write-Host "En cache : $file"
    return $file
  }
  Write-Host "Téléchargement $Url"
  $ProgressPreference = 'SilentlyContinue'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $file
  $actual = (Get-FileHash $file -Algorithm SHA256).Hash
  if ($actual -ne $Sha256) {
    Remove-Item $file -Force
    throw "SHA-256 inattendu pour $FileName : $actual"
  }
  return $file
}
