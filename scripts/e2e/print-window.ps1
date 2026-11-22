<#
.SYNOPSIS
  Captures the main window of one process with PrintWindow, never the screen.
.DESCRIPTION
  Used by scripts/e2e/wails-smoke.mjs to see the mpv video under the WebView2
  overlay, which a CDP screenshot (Page.captureScreenshot) does not contain
  (ADR 0006). PrintWindow(PW_RENDERFULLCONTENT) asks the window to paint itself:
  nothing else on the screen can end up in the image, and the window is neither
  moved nor activated. Pattern of scripts/spike/run-gate1-go.ps1.
  Prints one JSON line: size, and a coarse 64x36 sample of the client area
  (distinct colours, mean luminance, the 2304 luma bytes in base64) so the
  caller can tell video from a flat fill, and two captures apart, without
  looking at the pixels.
.EXAMPLE
  ./scripts/e2e/print-window.ps1 -ProcessId 1234 -Path C:\tmp\capture.png
#>
param(
  [Parameter(Mandatory)][int]$ProcessId,
  [Parameter(Mandatory)][string]$Path
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ViewerPrintWindow {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
'@
[ViewerPrintWindow]::SetProcessDPIAware() | Out-Null

$hwnd = (Get-Process -Id $ProcessId).MainWindowHandle
if ($hwnd -eq [IntPtr]::Zero) { throw "no main window for process $ProcessId" }
$r = New-Object ViewerPrintWindow+RECT
# DWMWA_EXTENDED_FRAME_BOUNDS = 9: visible bounds without the invisible resize border.
if ([ViewerPrintWindow]::DwmGetWindowAttribute($hwnd, 9, [ref]$r, 16) -ne 0) {
  [ViewerPrintWindow]::GetWindowRect($hwnd, [ref]$r) | Out-Null
}
$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
if ($w -le 0 -or $h -le 0) { throw "empty window ($w x $h)" }

$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [ViewerPrintWindow]::PrintWindow($hwnd, $hdc, 2)  # PW_RENDERFULLCONTENT
$g.ReleaseHdc($hdc)
$bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)

$colors = @{}
$luma = 0.0
$n = 0
$samples = New-Object byte[] (64 * 36)
for ($y = 0; $y -lt 36; $y++) {
  for ($x = 0; $x -lt 64; $x++) {
    $p = $bmp.GetPixel([int](($x + 0.5) * $w / 64), [int](($y + 0.5) * $h / 36))
    $colors[$p.ToArgb()] = $true
    $l = 0.2126 * $p.R + 0.7152 * $p.G + 0.0722 * $p.B
    $luma += $l
    $samples[$n] = [byte][math]::Min(255, [math]::Round($l))
    $n++
  }
}
$g.Dispose(); $bmp.Dispose()
[ordered]@{
  printed = $ok
  width = $w
  height = $h
  distinctColors = $colors.Count
  meanLuma = [math]::Round($luma / $n, 1)
  luma = [Convert]::ToBase64String($samples)
} | ConvertTo-Json -Compress
