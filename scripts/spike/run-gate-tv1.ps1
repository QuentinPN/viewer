<#
.SYNOPSIS
  Gate TV-1: plays one file on the Android TV device through the PoC activity
  and records diagnostics, screenshots and logcat in artifacts/gate-tv1/<run>/.
.DESCRIPTION
  The file is served from this PC by serve-file.mjs (127.0.0.1 only, token
  required in the X-Emby-Token header) and reached by the device through
  `adb reverse`, so nothing is copied to the device. Screenshots of real media
  stay in artifacts/ (ignored by git); note results in TESTING.md or the PR.
.EXAMPLE
  ./scripts/spike/run-gate-tv1.ps1 -Media '\\server\media\film.mkv'
#>
param(
  [Parameter(Mandatory)] [string] $Media,
  [string] $Device = '192.0.2.20:5555',
  [int] $Port = 8099,
  [string] $Token = 'gate-tv1-test-token',
  [int] $StartupSeconds = 12,
  [double] $Start = 0
)
$ErrorActionPreference = 'Stop'

$root = Resolve-Path "$PSScriptRoot\..\.."
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$run = Join-Path $root ("artifacts\gate-tv1\" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $run | Out-Null

function Adb { & $adb -s $Device @args }
function Shot($name) {
  Adb shell screencap -p /sdcard/gate-tv1.png | Out-Null
  Adb pull /sdcard/gate-tv1.png (Join-Path $run "$name.png") | Out-Null
}
function Key($code, $times = 1) { for ($i = 0; $i -lt $times; $i++) { Adb shell input keyevent $code | Out-Null } }

& $adb connect $Device | Out-Null
Adb reverse "tcp:$Port" "tcp:$Port" | Out-Null
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
  throw "Le port $Port est déjà utilisé (ancien serveur ?) : la mauvaise vidéo serait servie."
}

$env:REQUIRE_TOKEN = $Token
$server = Start-Process node -ArgumentList @("$root\scripts\spike\serve-file.mjs", "`"$Media`"", $Port) `
  -RedirectStandardOutput (Join-Path $run 'server.log') -RedirectStandardError (Join-Path $run 'server.err') `
  -NoNewWindow -PassThru
Remove-Item Env:REQUIRE_TOKEN
Start-Sleep -Seconds 1

try {
  Adb logcat -c
  Adb shell am force-stop io.github.quentinpn.viewer.tv.debug
  Adb shell am start -W -n io.github.quentinpn.viewer.tv.debug/io.github.quentinpn.viewer.tv.PlaybackPocActivity --es url "http://127.0.0.1:$Port/media" --es token $Token --es start $Start |
    Out-File (Join-Path $run 'am-start.txt')
  Start-Sleep -Seconds $StartupSeconds
  Shot '01-lecture'

  Key KEYCODE_DPAD_RIGHT 6          # +60 s
  Start-Sleep -Seconds 6
  Shot '02-apres-seek'

  Key KEYCODE_DPAD_CENTER           # pause
  Start-Sleep -Seconds 3
  Shot '03-pause'
  Key KEYCODE_DPAD_CENTER           # reprise

  Key KEYCODE_DPAD_UP               # piste de sous-titres suivante
  Start-Sleep -Seconds 4
  Shot '04-sous-titre-suivant'
  Key KEYCODE_DPAD_DOWN             # piste audio suivante
  Start-Sleep -Seconds 4

  Key KEYCODE_BACK                  # quitte : teardown mesuré par l'activité
  Start-Sleep -Seconds 3
} finally {
  Adb logcat -d -v time -s ViewerTV:* ViewerMpv:* AndroidRuntime:E DEBUG:* libc:F | Out-File -Encoding utf8 (Join-Path $run 'logcat.txt')
  # node may be a version-manager shim: stop the whole tree, not just the shim
  taskkill /T /F /PID $server.Id 2>$null | Out-Null
  Adb reverse --remove "tcp:$Port" | Out-Null
}
Write-Output "Résultats : $run"
