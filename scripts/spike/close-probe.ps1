<#
.SYNOPSIS
  Measures closing the app with the Windows close button, idle or during playback.
.DESCRIPTION
  Launches the debug build, optionally starts playback by typing -Media into the
  open form with real key presses, then posts WM_CLOSE (same as the title-bar X).
  Reports close latency, exit code, whether libmpv was loaded, and whether stderr
  contains the WebView2 "Failed to unregister class Chrome_WidgetWin_0" error.
  Requires the Vite dev server on port 1420 (`npm run dev`).
.EXAMPLE
  ./scripts/spike/close-probe.ps1 -Media 'D:\Vidéos\sample.mkv'
#>
param([int]$WaitSeconds = 6, [string]$Media, [int]$PlaySeconds = 6)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\..\.."
$exe = Join-Path $root 'src-tauri\target\debug\viewer.exe'
if (-not (Test-Path $exe)) { throw "Build introuvable : $exe" }
$out = Join-Path $root "artifacts\close-probe\$(Get-Date -Format 'yyyyMMdd-HHmmssfff')"
New-Item -ItemType Directory -Force $out | Out-Null
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class W {
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
}
'@
$p = Start-Process $exe -PassThru -RedirectStandardError "$out\stderr.log" -RedirectStandardOutput "$out\stdout.log"
$null = $p.Handle  # keep a handle so ExitCode is available after exit
Start-Sleep -Seconds $WaitSeconds
$p.Refresh()
if ($Media) {
  # The path field is the first focusable element of the open form.
  Add-Type -AssemblyName System.Windows.Forms
  [W]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
  Start-Sleep -Milliseconds 400
  $escaped = [regex]::Replace($Media, '[+^%~(){}\[\]]', '{$0}')
  [System.Windows.Forms.SendKeys]::SendWait('{TAB}')
  [System.Windows.Forms.SendKeys]::SendWait($escaped)
  [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
  Start-Sleep -Seconds $PlaySeconds
  $p.Refresh()
}
$libmpvLoaded = [bool]($p.Modules | Where-Object ModuleName -eq 'libmpv-2.dll')
$sw = [Diagnostics.Stopwatch]::StartNew()
[W]::PostMessage($p.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null  # WM_CLOSE
$exited = $p.WaitForExit(15000)
$sw.Stop()
if (-not $exited) { Stop-Process -Id $p.Id -Force }
Start-Sleep -Milliseconds 500
[ordered]@{
  libmpvLoaded = $libmpvLoaded
  exited = $exited
  exitCode = if ($exited) { $p.ExitCode } else { $null }
  closeMs = $sw.ElapsedMilliseconds
  leftoverProcesses = @(Get-Process viewer -ErrorAction SilentlyContinue).Count
  webview2Error = [bool](Select-String -Path "$out\stderr.log" -Pattern 'Chrome_WidgetWin' -Quiet)
  output = "$out"
} | ConvertTo-Json
