# E2E: sign in through the UI, browse libraries, scroll to load more pages, resize
# narrow, then sign out. Needs a debug build (cargo build) and the Vite dev server.
# Credentials come from VIEWER_EMBY_USER / VIEWER_EMBY_PASSWORD, never from arguments;
# screenshots go to artifacts/gate2/<run>. Starts and ends signed out.
param([string]$Url = $env:VIEWER_EMBY_URL)
$ErrorActionPreference = 'Stop'
if (-not $Url -or -not $env:VIEWER_EMBY_USER -or -not $env:VIEWER_EMBY_PASSWORD) { throw 'VIEWER_EMBY_URL, VIEWER_EMBY_USER and VIEWER_EMBY_PASSWORD are required' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$exe = Join-Path $root 'src-tauri\target\debug\viewer.exe'
$out = Join-Path $root "artifacts\gate2\browse-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Force $out | Out-Null
Add-Type -AssemblyName System.Drawing, System.Windows.Forms
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class W3 {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int hh, bool r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, int d, IntPtr e);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int a, out RECT r, int s);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
'@
[W3]::SetProcessDPIAware() | Out-Null
function Rect($p) { $p.Refresh(); $r = New-Object W3+RECT; [W3]::DwmGetWindowAttribute($p.MainWindowHandle, 9, [ref]$r, 16) | Out-Null; $r }
function Shot($p, $name) {
  $r = Rect $p
  $bmp = New-Object System.Drawing.Bitmap ($r.Right - $r.Left), ($r.Bottom - $r.Top)
  $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
  $bmp.Save("$out\$name.png"); $g.Dispose(); $bmp.Dispose()
}
function Keys($p, [string[]]$seq) {
  $p.Refresh(); [W3]::SetForegroundWindow($p.MainWindowHandle) | Out-Null; Start-Sleep -Milliseconds 300
  foreach ($k in $seq) { [System.Windows.Forms.SendKeys]::SendWait($k); Start-Sleep -Milliseconds 150 }
}
# Mouse wheel over the content area (right of the sidebar). Negative = down.
function Wheel($p, [int]$notches) {
  $r = Rect $p
  [W3]::SetCursorPos($r.Left + [int](($r.Right - $r.Left) * 0.65), $r.Top + [int](($r.Bottom - $r.Top) / 2)) | Out-Null
  for ($i = 0; $i -lt [math]::Abs($notches); $i++) {
    [W3]::mouse_event(0x0800, 0, 0, [math]::Sign($notches) * 120, [IntPtr]::Zero); Start-Sleep -Milliseconds 40
  }
}
function Esc([string]$s) { [regex]::Replace($s, '[+^%~(){}\[\]]', '{$0}') }

$p = Start-Process $exe -PassThru -RedirectStandardError "$out\stderr.log" -RedirectStandardOutput "$out\stdout.log"
$null = $p.Handle; Start-Sleep -Seconds 6
$r = Rect $p; [W3]::MoveWindow($p.MainWindowHandle, $r.Left, $r.Top, 1280, 800, $true) | Out-Null; Start-Sleep -Seconds 1

# Sign in (the address may be prefilled: select all before typing).
Keys $p @('{TAB}', '^a', (Esc $Url), '{ENTER}'); Start-Sleep -Seconds 2
Keys $p @((Esc $env:VIEWER_EMBY_USER), '{TAB}', (Esc $env:VIEWER_EMBY_PASSWORD), '{ENTER}'); Start-Sleep -Seconds 5
Shot $p '1-first-library'

# Sidebar: Tab reaches the first library button, Tab again the second.
Keys $p @('{TAB}', '{TAB}', '{ENTER}'); Start-Sleep -Seconds 4
Shot $p '2-second-library'

# Scroll down: the sentinel must load the next pages.
Wheel $p -40; Start-Sleep -Seconds 3
Shot $p '3-scrolled'
Wheel $p -80; Start-Sleep -Seconds 3
Shot $p '4-scrolled-more'
# Posters scrolled past are still downloading (cold resize ~200 ms each on the NAS).
Start-Sleep -Seconds 10
Shot $p '4b-posters-caught-up'

# Narrow window: the sidebar becomes a top bar.
$r = Rect $p; [W3]::MoveWindow($p.MainWindowHandle, $r.Left, $r.Top, 700, 700, $true) | Out-Null; Start-Sleep -Seconds 2
Shot $p '5-narrow'
$r = Rect $p; [W3]::MoveWindow($p.MainWindowHandle, $r.Left, $r.Top, 1280, 800, $true) | Out-Null; Start-Sleep -Seconds 1

# Sign out: from the 2nd library, Tab past the remaining libraries and the player prototype.
Keys $p @('{TAB}', '{TAB}', '{TAB}', '{TAB}', '{ENTER}'); Start-Sleep -Seconds 3
Shot $p '6-signed-out'
$p.Refresh(); [W3]::PostMessage($p.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
$null = $p.WaitForExit(10000)

[ordered]@{
  out = $out
  exitCode = $p.ExitCode
  credentialEntries = @(cmdkey /list | Select-String 'Viewer').Count
} | ConvertTo-Json
