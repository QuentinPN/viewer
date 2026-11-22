<#
.SYNOPSIS
  Drives the Go + Wails v3 playback spike (ADR 0128) and collects Gate 1 evidence.
.DESCRIPTION
  Builds go-app/cmd/spike without cgo, launches it in -selftest mode, captures the
  spike window only (PrintWindow, never the screen) at each checkpoint written by the
  spike (<Out>/checkpoint.txt), posts one synthetic Space key to the spike's own
  WebView2 window (no OS-level input; does not prove real focus, keyboard stays
  UNVERIFIED), and measures how long the process takes to exit after the scenario ends.
  Modelled on run-gate1.ps1. Screenshots must be reviewed by eye (ADR 0003).
  Synthetic media: `go-app/bin/spike.exe -gen <dir>` (spike.mkv, audio2.mka, spike.ass).
.EXAMPLE
  ./scripts/spike/run-gate1-go.ps1 -Media C:\tmp\media\spike.mkv -Out C:\tmp\gate1-go\local
  ./scripts/spike/run-gate1-go.ps1 -Media http://127.0.0.1:8099/media -Sub C:\tmp\media\spike.ass -Audio2 C:\tmp\media\audio2.mka -Out C:\tmp\gate1-go\http
#>
param(
  [Parameter(Mandatory)][string]$Media,
  [string]$Out,
  [string]$Sub,
  [string]$Audio2,
  [switch]$NoBuild,
  [int]$TimeoutSeconds = 180
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\..\.."
$goApp = Join-Path $root 'go-app'
$exe = Join-Path $goApp 'bin\spike.exe'

$buildSeconds = $null
if (-not $NoBuild) {
  $env:CGO_ENABLED = '0'
  Push-Location $goApp
  try {
    $t = Measure-Command { go build -o $exe ./cmd/spike }
    if ($LASTEXITCODE -ne 0) { throw 'go build a échoué' }
  } finally { Pop-Location }
  $buildSeconds = [math]::Round($t.TotalSeconds, 2)
}
if (-not (Test-Path $exe)) { throw "Build introuvable : $exe" }

if (-not $Out) { $Out = Join-Path $root "artifacts\gate1-go\$(Get-Date -Format 'yyyyMMdd-HHmmss')" }
New-Item -ItemType Directory -Force $Out | Out-Null
Remove-Item (Join-Path $Out '*') -Include checkpoint.txt, report.json, hwnd.txt, watchdog.txt, quit-fallback.txt -ErrorAction SilentlyContinue

Add-Type -AssemblyName System.Drawing, System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class WinGo {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr parent, IntPtr after, string cls, string title);
  // Depth-first search for a descendant window of the given class.
  public static IntPtr FindDescendant(IntPtr parent, string cls) {
    IntPtr child = IntPtr.Zero;
    while ((child = FindWindowEx(parent, child, null, null)) != IntPtr.Zero) {
      var sb = new System.Text.StringBuilder(256);
      GetClassName(child, sb, 256);
      if (sb.ToString() == cls) return child;
      IntPtr found = FindDescendant(child, cls);
      if (found != IntPtr.Zero) return found;
    }
    return IntPtr.Zero;
  }
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr h, System.Text.StringBuilder sb, int max);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
'@
[WinGo]::SetProcessDPIAware() | Out-Null

function Save-WindowCapture([IntPtr]$hwnd, [string]$name) {
  $r = New-Object WinGo+RECT
  # DWMWA_EXTENDED_FRAME_BOUNDS = 9: visible bounds without the invisible resize border.
  [WinGo]::DwmGetWindowAttribute($hwnd, 9, [ref]$r, 16) | Out-Null
  $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
  if ($w -le 0 -or $h -le 0) { return }
  # PrintWindow(PW_RENDERFULLCONTENT = 2) asks the spike window to paint itself:
  # nothing else on the screen can end up in the image.
  $bmp = New-Object System.Drawing.Bitmap $w, $h
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $hdc = $g.GetHdc()
  [WinGo]::PrintWindow($hwnd, $hdc, 2) | Out-Null
  $g.ReleaseHdc($hdc)
  $bmp.Save((Join-Path $Out "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
}

$spikeArgs = @('-selftest', '-media', $Media, '-out', $Out)
if ($Sub) { $spikeArgs += @('-sub', $Sub) }
if ($Audio2) { $spikeArgs += @('-audio2', $Audio2) }
$proc = Start-Process -FilePath $exe -ArgumentList $spikeArgs -WorkingDirectory $goApp -PassThru `
  -RedirectStandardError (Join-Path $Out 'stderr.log') -RedirectStandardOutput (Join-Path $Out 'stdout.log')

$checkpointFile = Join-Path $Out 'checkpoint.txt'
$hwndFile = Join-Path $Out 'hwnd.txt'
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$last = ''
$seen = [ordered]@{}
$doneAt = $null
$hwnd = [IntPtr]::Zero
$keyboard = $null
while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
  if ($hwnd -eq [IntPtr]::Zero -and (Test-Path $hwndFile)) {
    $hwnd = [IntPtr][long](Get-Content $hwndFile -Raw).Trim()
  }
  $cp = if (Test-Path $checkpointFile) { (Get-Content $checkpointFile -Raw -ErrorAction SilentlyContinue) } else { '' }
  $cp = "$cp".Trim()
  if ($cp -and $cp -ne $last) {
    $last = $cp
    if ($cp -eq 'done') {
      $doneAt = Get-Date
    } elseif ($cp -eq 'keyboard-press-space') {
      Start-Sleep -Milliseconds 300
      # Synthetic message posted straight to WebView2's input window: no OS input,
      # so it can never reach another application. Does not prove real focus.
      # Never post without a known window. The Chrome_RenderWidgetHostHWND lives in
      # the msedgewebview2 process, so ownership is checked on the root window
      # ($hwnd, the id the spike wrote) that the descendant was found under.
      $target = [IntPtr]::Zero
      $ownerPid = 0
      if ($hwnd -ne [IntPtr]::Zero) {
        [WinGo]::GetWindowThreadProcessId($hwnd, [ref]$ownerPid) | Out-Null
        if ($ownerPid -eq $proc.Id) { $target = [WinGo]::FindDescendant($hwnd, 'Chrome_RenderWidgetHostHWND') }
      }
      $method = "PostMessage WM_KEYDOWN/WM_CHAR/WM_KEYUP to Chrome_RenderWidgetHostHWND ($target), synthetic"
      if ($target -eq [IntPtr]::Zero) {
        $method = "keyboard check skipped: no spike-owned window (hwnd=$hwnd, pid=$ownerPid)"
      }
      if ($target -ne [IntPtr]::Zero) {
        $scan = 0x39  # Space
        [WinGo]::PostMessage($target, 0x0100, [IntPtr]0x20, [IntPtr](1 -bor ($scan -shl 16))) | Out-Null
        [WinGo]::PostMessage($target, 0x0102, [IntPtr]0x20, [IntPtr](1 -bor ($scan -shl 16))) | Out-Null
        [WinGo]::PostMessage($target, 0x0101, [IntPtr]0x20, [IntPtr]([long]1 -bor ($scan -shl 16) -bor (3L -shl 30))) | Out-Null
      }
      $keyboard = [ordered]@{ method = $method; verified = $false }
    } elseif (-not $seen.Contains($cp)) {
      Start-Sleep -Milliseconds 1200  # let the frame settle after the step
      Save-WindowCapture $hwnd $cp
      $seen[$cp] = $true
    }
  }
  Start-Sleep -Milliseconds 100
}

$exited = $proc.WaitForExit(15000)
$exitAfterDoneMs = if ($exited -and $doneAt) { [int]($proc.ExitTime - $doneAt).TotalMilliseconds } else { $null }
if (-not $exited) { Stop-Process -Id $proc.Id -Force }

$summary = [ordered]@{
  media = Split-Path $Media -Leaf
  buildSeconds = $buildSeconds
  processExitedBySelf = $exited
  exitCode = if ($exited) { $proc.ExitCode } else { $null }
  exitAfterDoneMs = $exitAfterDoneMs   # includes the spike's 500 ms 'done' hold
  keyboard = $keyboard
  watchdogFired = Test-Path (Join-Path $Out 'watchdog.txt')
  quitFallback = Test-Path (Join-Path $Out 'quit-fallback.txt')
  lastCheckpoint = $last
  screenshots = @($seen.Keys)
  os = (Get-CimInstance Win32_OperatingSystem).Caption + ' ' + [Environment]::OSVersion.Version
  gpu = @(Get-CimInstance Win32_VideoController | ForEach-Object { "$($_.Name) ($($_.DriverVersion))" })
  primaryScreen = "$([System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Width)x$([System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Height)"
}
$summary | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $Out 'driver.json')
Write-Output "Résultats : $Out"
if (Test-Path (Join-Path $Out 'report.json')) {
  $report = Get-Content (Join-Path $Out 'report.json') -Raw | ConvertFrom-Json
  $report.checks | ForEach-Object { '{0,-28} {1,-5} {2}' -f $_.name, $(if ($_.passed) { 'PASS' } else { 'FAIL' }), $_.detail }
}
$summary | ConvertTo-Json
