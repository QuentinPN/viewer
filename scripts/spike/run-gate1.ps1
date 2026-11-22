<#
.SYNOPSIS
  Drives the Gate 1 playback scenario against the real app and collects evidence.
.DESCRIPTION
  Starts the Vite dev server if needed, launches the debug build with the spike
  scenario enabled (src-tauri/src/spike.rs), takes real screen captures of the app
  window at each checkpoint, sends a real Space key press for the keyboard check,
  and verifies the process exits by itself. Output goes to artifacts/gate1/<run>/
  (git-ignored; note results in TESTING.md or the PR).
  Prerequisite: `scripts/with-msvc.ps1 cargo build --manifest-path src-tauri/Cargo.toml`.
.EXAMPLE
  ./scripts/spike/run-gate1.ps1 -Media 'D:\Vidéos\sample.mkv' -Mode child
#>
param(
  [Parameter(Mandatory)][string]$Media,
  [ValidateSet('parent', 'child', 'separate')][string]$Mode = 'parent',
  # Test-only value sent as X-Emby-Token header (pair with serve-file.mjs REQUIRE_TOKEN).
  [string]$HttpToken,
  [int]$TimeoutSeconds = 180
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\..\.."
$exe = Join-Path $root 'src-tauri\target\debug\viewer.exe'
if (-not (Test-Path $exe)) { throw "Build introuvable : $exe" }

$run = "$Mode-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$out = Join-Path $root "artifacts\gate1\$run"
New-Item -ItemType Directory -Force $out | Out-Null

Add-Type -AssemblyName System.Drawing, System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class Win {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
'@
[Win]::SetProcessDPIAware() | Out-Null

function Save-WindowCapture([IntPtr]$hwnd, [string]$name) {
  $r = New-Object Win+RECT
  # DWMWA_EXTENDED_FRAME_BOUNDS = 9: visible bounds without the invisible resize border.
  [Win]::DwmGetWindowAttribute($hwnd, 9, [ref]$r, 16) | Out-Null
  $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
  if ($w -le 0 -or $h -le 0) { return }
  $bmp = New-Object System.Drawing.Bitmap $w, $h
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
  $bmp.Save((Join-Path $out "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
}

# Vite dev server (debug builds load the frontend from devUrl).
$viteJob = $null
if (-not (Test-NetConnection -ComputerName localhost -Port 1420 -InformationLevel Quiet -WarningAction SilentlyContinue)) {
  $viteJob = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm run dev' -WorkingDirectory $root -WindowStyle Hidden -PassThru
  $deadline = (Get-Date).AddSeconds(30)
  while (-not (Test-NetConnection -ComputerName localhost -Port 1420 -InformationLevel Quiet -WarningAction SilentlyContinue)) {
    if ((Get-Date) -gt $deadline) { throw 'Vite ne démarre pas sur le port 1420' }
    Start-Sleep -Milliseconds 500
  }
}

$env:VIEWER_SPIKE_MEDIA = $Media
$env:VIEWER_SPIKE_OUT = $out
$env:VIEWER_VIDEO_MODE = $Mode
if ($HttpToken) { $env:VIEWER_SPIKE_HTTP_TOKEN = $HttpToken }
$proc = Start-Process -FilePath $exe -PassThru -RedirectStandardError (Join-Path $out 'stderr.log') -RedirectStandardOutput (Join-Path $out 'stdout.log')
Remove-Item Env:VIEWER_SPIKE_MEDIA, Env:VIEWER_SPIKE_OUT, Env:VIEWER_VIDEO_MODE
Remove-Item Env:VIEWER_SPIKE_HTTP_TOKEN -ErrorAction SilentlyContinue

$captures = @('startup', 'playing', 'seeked-subtitles', 'fullscreen', 'resized', 'paused', 'closed')
$seen = @{}
$checkpointFile = Join-Path $out 'checkpoint.txt'
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$last = ''
while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
  $cp = if (Test-Path $checkpointFile) { (Get-Content $checkpointFile -Raw).Trim() } else { '' }
  if ($cp -and $cp -ne $last) {
    $last = $cp
    $proc.Refresh()
    $hwnd = $proc.MainWindowHandle
    if ($cp -eq 'keyboard-press-space') {
      Start-Sleep -Milliseconds 300
      [Win]::SetForegroundWindow($hwnd) | Out-Null
      Start-Sleep -Milliseconds 300
      [System.Windows.Forms.SendKeys]::SendWait(' ')
    } elseif (($captures -contains $cp -or $cp -match '^(audio|subtitle)-') -and -not $seen[$cp]) {
      Start-Sleep -Milliseconds 1200  # let the frame settle after the step
      Save-WindowCapture $hwnd $cp
      $seen[$cp] = $true
    }
  }
  Start-Sleep -Milliseconds 150
}

$exitedCleanly = $proc.WaitForExit(15000)
if (-not $exitedCleanly) { Stop-Process -Id $proc.Id -Force }
if ($viteJob) { Get-CimInstance Win32_Process -Filter "ParentProcessId=$($viteJob.Id)" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Stop-Process -Id $viteJob.Id -Force -ErrorAction SilentlyContinue }

$summary = [ordered]@{
  run = $run
  mode = $Mode
  processExitedBySelf = $exitedCleanly
  exitCode = if ($exitedCleanly) { $proc.ExitCode } else { $null }
  lastCheckpoint = $last
  screenshots = @($seen.Keys)
  os = (Get-CimInstance Win32_OperatingSystem).Caption + ' ' + [Environment]::OSVersion.Version
  gpu = @(Get-CimInstance Win32_VideoController | ForEach-Object { "$($_.Name) ($($_.DriverVersion))" })
  primaryScreen = "$([System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Width)x$([System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Height)"
}
$summary | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $out 'driver.json')
Write-Output "Résultats : $out"
if (Test-Path (Join-Path $out 'report.json')) {
  $report = Get-Content (Join-Path $out 'report.json') -Raw | ConvertFrom-Json
  $report.checks | ForEach-Object { '{0,-24} {1,-6} {2}' -f $_.name, $(if ($_.passed) { 'PASS' } else { 'FAIL' }), $_.detail }
}
$summary | ConvertTo-Json
