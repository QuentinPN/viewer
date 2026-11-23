<#
.SYNOPSIS
  Builds Viewer_<version>_x64-setup.exe, the NSIS installer of the Wails build.
.DESCRIPTION
  Packages go-app/bin/viewer.exe (from
  `npm run build:wails:exe`: CGO_ENABLED=0, -H=windowsgui) and vendor/mpv/libmpv-2.dll with
  go-app/build/windows/installer.nsi. The version comes from package.json.
  makensis is looked up, never installed: -Makensis, $env:MAKENSIS, PATH, the NSIS cache
  the former Tauri CLI left on this PC (%LOCALAPPDATA%\tauri\NSIS), then
  Program Files (x86)\NSIS. The icon is go-app/build/windows/icon.ico.
.PARAMETER Build
  Runs `npm run build:wails:exe` first (also done when the exe is missing).
.PARAMETER ExePath
  The exe to package. Default: go-app/bin/viewer.exe. It must be named viewer.exe
  once installed: the name the installed app (first shipped by the Tauri build) already has.
.PARAMETER OutDir
  Where the setup exe goes. Default: go-app/bin.
.EXAMPLE
  pwsh scripts/build-wails-installer.ps1 -OutDir $env:TEMP\installer
#>
param(
  [switch]$Build,
  [string]$ExePath,
  [string]$LibmpvPath,
  [string]$OutDir,
  [string]$Makensis
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'go-app\bin\viewer.exe' }
if (-not $LibmpvPath) { $LibmpvPath = Join-Path $root 'vendor\mpv\libmpv-2.dll' }
if (-not $OutDir) { $OutDir = Join-Path $root 'go-app\bin' }
$nsi = Join-Path $root 'go-app\build\windows\installer.nsi'
$icon = Join-Path $root 'go-app\build\windows\icon.ico'

$version = (Get-Content (Join-Path $root 'package.json') -Raw | ConvertFrom-Json).version
if ($version -notmatch '^(\d+\.\d+\.\d+)') { throw "Version illisible dans package.json : $version" }
$versionWithBuild = "$($Matches[1]).0"

if ($Build -or -not (Test-Path $ExePath)) {
  Push-Location $root
  try {
    npm run build:wails:exe
    if ($LASTEXITCODE -ne 0) { throw "npm run build:wails:exe a échoué ($LASTEXITCODE)" }
  } finally { Pop-Location }
}
foreach ($f in $ExePath, $LibmpvPath, $nsi, $icon) {
  if (-not (Test-Path $f)) { throw "Introuvable : $f (libmpv : scripts/fetch-libmpv.ps1)" }
}

if (-not $Makensis) {
  $candidates = @(
    $env:MAKENSIS,
    (Get-Command makensis -ErrorAction SilentlyContinue).Source,
    (Join-Path $env:LOCALAPPDATA 'tauri\NSIS\makensis.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'NSIS\makensis.exe')
  )
  $Makensis = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}
if (-not $Makensis) {
  throw 'makensis introuvable : installer NSIS 3.x ou passer -Makensis / $env:MAKENSIS.'
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
$out = Join-Path (Resolve-Path $OutDir) "Viewer_${version}_x64-setup.exe"

& $Makensis /INPUTCHARSET UTF8 /V2 `
  "/DVERSION=$version" "/DVERSIONWITHBUILD=$versionWithBuild" `
  "/DOUTFILE=$out" "/DMAINBINARYSRCPATH=$((Resolve-Path $ExePath).Path)" `
  "/DLIBMPVSRCPATH=$((Resolve-Path $LibmpvPath).Path)" `
  "/DINSTALLERICON=$icon" $nsi
if ($LASTEXITCODE -ne 0) { throw "makensis a échoué ($LASTEXITCODE)" }
Write-Host $out
