# E2E: sign in through the UI, open series and film detail pages by keyboard and
# mouse, return with Escape, check the grid keeps its scroll, then sign out. Needs a
# debug build (cargo build) and the Vite dev server. Credentials come from
# VIEWER_EMBY_USER / VIEWER_EMBY_PASSWORD, never from arguments; screenshots go to
# artifacts/gate2/<run>. Starts and ends signed out.
param([string]$Url = $env:VIEWER_EMBY_URL)
$ErrorActionPreference = 'Stop'
if (-not $Url -or -not $env:VIEWER_EMBY_USER -or -not $env:VIEWER_EMBY_PASSWORD) { throw 'VIEWER_EMBY_URL, VIEWER_EMBY_USER and VIEWER_EMBY_PASSWORD are required' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$exe = Join-Path $root 'src-tauri\target\debug\viewer.exe'
$out = Join-Path $root "artifacts\gate2\detail-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Force $out | Out-Null
Add-Type -AssemblyName System.Drawing, System.Windows.Forms
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class W4 {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int hh, bool r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, int d, IntPtr e);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int a, out RECT r, int s);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
'@
[W4]::SetProcessDPIAware() | Out-Null
function Rect($p) { $p.Refresh(); $r = New-Object W4+RECT; [W4]::DwmGetWindowAttribute($p.MainWindowHandle, 9, [ref]$r, 16) | Out-Null; $r }
function Shot($p, $name) {
  $r = Rect $p
  $bmp = New-Object System.Drawing.Bitmap ($r.Right - $r.Left), ($r.Bottom - $r.Top)
  $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
  $bmp.Save("$out\$name.png"); $g.Dispose(); $bmp.Dispose()
}
# Keys are only sent while the app is the foreground window: SendKeys types into
# whatever window has focus, and the sequence may hold the password.
function Focus($p) {
  for ($i = 0; $i -lt 10; $i++) {
    $p.Refresh(); [W4]::SetForegroundWindow($p.MainWindowHandle) | Out-Null; Start-Sleep -Milliseconds 300
    if ([W4]::GetForegroundWindow() -eq $p.MainWindowHandle) { return }
  }
  Stop-Process -Id $p.Id -Force
  throw 'Viewer is not the foreground window: no key sent'
}
function Keys($p, [string[]]$seq) {
  foreach ($k in $seq) { Focus $p; [System.Windows.Forms.SendKeys]::SendWait($k); Start-Sleep -Milliseconds 150 }
}
# Cursor at a fraction of the window. Negative wheel = down.
function Point($p, [double]$fx, [double]$fy) {
  $r = Rect $p
  [W4]::SetCursorPos($r.Left + [int](($r.Right - $r.Left) * $fx), $r.Top + [int](($r.Bottom - $r.Top) * $fy)) | Out-Null
}
function Wheel($p, [int]$notches) {
  Point $p 0.65 0.5
  for ($i = 0; $i -lt [math]::Abs($notches); $i++) {
    [W4]::mouse_event(0x0800, 0, 0, [math]::Sign($notches) * 120, [IntPtr]::Zero); Start-Sleep -Milliseconds 40
  }
}
function Click($p, [double]$fx, [double]$fy) {
  Focus $p; Point $p $fx $fy; Start-Sleep -Milliseconds 100
  [W4]::mouse_event(0x02, 0, 0, 0, [IntPtr]::Zero); [W4]::mouse_event(0x04, 0, 0, 0, [IntPtr]::Zero)
}
function Size($p, [int]$w, [int]$h) { $r = Rect $p; [W4]::MoveWindow($p.MainWindowHandle, $r.Left, $r.Top, $w, $h, $true) | Out-Null; Start-Sleep -Seconds 1 }
function Esc([string]$s) { [regex]::Replace($s, '[+^%~(){}\[\]]', '{$0}') }

$p = Start-Process $exe -PassThru -RedirectStandardError "$out\stderr.log" -RedirectStandardOutput "$out\stdout.log"
$null = $p.Handle; Start-Sleep -Seconds 6
Size $p 1280 800
# A cold Vite dev server can take several seconds more to serve the first page.
Start-Sleep -Seconds 6
Shot $p '00-start'

Keys $p @('{TAB}', '^a', (Esc $Url), '{ENTER}'); Start-Sleep -Seconds 2
Keys $p @((Esc $env:VIEWER_EMBY_USER), '{TAB}', (Esc $env:VIEWER_EMBY_PASSWORD), '{ENTER}'); Start-Sleep -Seconds 5

# First library (series). Tab order: 4 libraries, player prototype, sign-out, then cards.
Keys $p (@('{TAB}') * 7 + '{ENTER}'); Start-Sleep -Seconds 3
Shot $p '01-series-detail'
Wheel $p -10; Start-Sleep -Seconds 2
Shot $p '02-series-episodes'
Keys $p @('{ESC}'); Start-Sleep -Seconds 1
Shot $p '03-back-focus-card1'

# Cards 2 and 3: other series, hopefully one with several seasons.
foreach ($n in 2, 3) {
  Keys $p @('{TAB}', '{ENTER}'); Start-Sleep -Seconds 3
  Shot $p "04-series-card$n"
  # Stop where the season buttons are visible.
  Wheel $p -4; Start-Sleep -Seconds 2
  Shot $p "05-series-card$n-episodes"
  if ($n -eq 3) {
    # Focus is on the title; the next stops are the season buttons: pick the second.
    Keys $p @('{TAB}', '{TAB}', '{ENTER}'); Start-Sleep -Seconds 2
    Shot $p '05b-second-season'
  }
  Keys $p @('{ESC}'); Start-Sleep -Seconds 1
}

# Films: from card 3, Shift+Tab back over cards 2, 1, sign-out, prototype, libraries 4, 3, 2.
Keys $p (@('+{TAB}') * 7 + '{ENTER}'); Start-Sleep -Seconds 4
Keys $p (@('{TAB}') * 5 + '{ENTER}'); Start-Sleep -Seconds 3
Shot $p '06-film-hero'
Wheel $p -10; Start-Sleep -Seconds 2
Shot $p '07-film-tech'
Size $p 700 700; Start-Sleep -Seconds 1
Wheel $p 20; Start-Sleep -Seconds 1
Shot $p '08-film-narrow'
Size $p 1280 800

# Scroll the grid, open a card with the mouse, come back: the scroll must be kept.
Keys $p @('{ESC}'); Start-Sleep -Seconds 1
Wheel $p -30; Start-Sleep -Seconds 3
Shot $p '09-grid-scrolled'
Click $p 0.45 0.45; Start-Sleep -Seconds 3
Shot $p '10-detail-from-mouse'
Keys $p @('{ESC}'); Start-Sleep -Seconds 1
Shot $p '11-grid-scroll-kept'

# Sign out with the sidebar button (bottom left).
Click $p 0.07 0.94; Start-Sleep -Seconds 3
Shot $p '12-signed-out'
$p.Refresh(); [W4]::PostMessage($p.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
$null = $p.WaitForExit(10000)

[ordered]@{
  out = $out
  exitCode = $p.ExitCode
  credentialEntries = @(cmdkey /list | Select-String 'Viewer').Count
} | ConvertTo-Json
