<#
.SYNOPSIS
  Runs a command inside an MSVC x64 developer environment.
.DESCRIPTION
  Local workaround: on this machine the newest Visual Studio install lacks the x64
  C++ libraries (msvcrt.lib), and rustc picks it by default (LNK1104). This script
  finds the newest VS install that *does* have them, imports its vcvars64
  environment, then runs the given command. rustc honours VCINSTALLDIR/LIB when set.
  Not needed on CI runners (complete VS images).
.EXAMPLE
  ./scripts/with-msvc.ps1 cargo build --manifest-path src-tauri/Cargo.toml
#>
# Plain $args (no param block): an advanced-function param block would bind
# arguments such as `-D warnings` to PowerShell common parameters (-Debug).
$Command = $args
if (-not $Command) { throw "Usage: with-msvc.ps1 <commande> [arguments...]" }
$ErrorActionPreference = 'Stop'

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installs = & $vswhere -products * -prerelease -property installationPath
$vcvars = $null
foreach ($root in $installs) {
  $tools = Get-ChildItem "$root\VC\Tools\MSVC" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
  foreach ($t in $tools) {
    # Some installs have the libraries but no vcvars64.bat or no C++ headers.
    if ((Test-Path "$($t.FullName)\lib\x64\msvcrt.lib") -and
        (Test-Path "$($t.FullName)\include\vcruntime.h") -and
        (Test-Path "$root\VC\Auxiliary\Build\vcvars64.bat")) {
      $vcvars = "$root\VC\Auxiliary\Build\vcvars64.bat"
      break
    }
  }
  if ($vcvars) { break }
}
if (-not $vcvars) { throw "Aucune installation Visual Studio avec les bibliothèques C++ x64 (msvcrt.lib)." }

# Import the environment produced by vcvars64.bat into this PowerShell session.
# vcvars itself calls vswhere, so its folder must be on PATH.
$env:Path = "$(Split-Path $vswhere);$env:Path"
$ErrorActionPreference = 'Continue'
cmd /c "`"$vcvars`" >nul 2>&1 && set" | ForEach-Object {
  if ($_ -match '^([^=]+)=(.*)$') { Set-Item -Path "env:$($Matches[1])" -Value $Matches[2] }
}
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"

$exe, $rest = $Command
& $exe @rest
exit $LASTEXITCODE
