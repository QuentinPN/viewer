# E2E: sign in through the real UI, restart to check restore, then sign out.
# Needs a debug build (cargo build). Credentials come from VIEWER_EMBY_USER / VIEWER_EMBY_PASSWORD,
# never from arguments; screenshots go to artifacts/gate2/<run>. Signs out at the end.
param([string]$Url = $env:VIEWER_EMBY_URL)
$ErrorActionPreference = 'Stop'
if (-not $Url -or -not $env:VIEWER_EMBY_USER -or -not $env:VIEWER_EMBY_PASSWORD) { throw 'VIEWER_EMBY_URL, VIEWER_EMBY_USER and VIEWER_EMBY_PASSWORD are required' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$exe = Join-Path $root 'src-tauri\target\debug\viewer.exe'
$out = Join-Path $root "artifacts\gate2\e2e-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Force $out | Out-Null
Add-Type -AssemblyName System.Drawing, System.Windows.Forms
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class W2 {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int a, out RECT r, int s);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
'@
[W2]::SetProcessDPIAware() | Out-Null
function Shot($p, $name) {
  $p.Refresh(); $r = New-Object W2+RECT
  [W2]::DwmGetWindowAttribute($p.MainWindowHandle, 9, [ref]$r, 16) | Out-Null
  $bmp = New-Object System.Drawing.Bitmap ($r.Right - $r.Left), ($r.Bottom - $r.Top)
  $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
  $bmp.Save("$out\$name.png"); $g.Dispose(); $bmp.Dispose()
}
function Keys($p, [string[]]$seq) {
  $p.Refresh(); [W2]::SetForegroundWindow($p.MainWindowHandle) | Out-Null; Start-Sleep -Milliseconds 300
  foreach ($k in $seq) { [System.Windows.Forms.SendKeys]::SendWait($k); Start-Sleep -Milliseconds 150 }
}
function Esc([string]$s) { [regex]::Replace($s, '[+^%~(){}\[\]]', '{$0}') }
function Start-App($tag) {
  $p = Start-Process $exe -PassThru -RedirectStandardError "$out\$tag-stderr.log" -RedirectStandardOutput "$out\$tag-stdout.log"
  $null = $p.Handle; Start-Sleep -Seconds 6; $p
}
function Close-App($p) {
  $p.Refresh(); [W2]::PostMessage($p.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
  $null = $p.WaitForExit(10000)
}

# 1. First launch: onboarding, wrong password, then the right one.
$p = Start-App 'run1'
Shot $p '1-onboarding'
Keys $p @('{TAB}', (Esc $Url), '{ENTER}'); Start-Sleep -Seconds 2
Shot $p '2-server-found'
Keys $p @((Esc $env:VIEWER_EMBY_USER), '{TAB}', 'mauvais-mot-de-passe', '{ENTER}'); Start-Sleep -Seconds 2
Shot $p '3-wrong-password'
# Focus is back in the password field after a wrong password.
Keys $p @((Esc $env:VIEWER_EMBY_PASSWORD), '{ENTER}'); Start-Sleep -Seconds 2
Shot $p '4-signed-in'
Close-App $p

# 2. Restart: the session must be restored from the credential store.
$p = Start-App 'run2'
Shot $p '5-restored'
# Sign out: Tab to "Prototype de lecture", Tab to "Se déconnecter".
Keys $p @('{TAB}', '{TAB}', '{ENTER}'); Start-Sleep -Seconds 2
Shot $p '6-signed-out'
Close-App $p

# 3. Restart after sign-out: onboarding with the address prefilled.
$p = Start-App 'run3'
Shot $p '7-after-sign-out'
Close-App $p

$cfg = Join-Path $env:APPDATA 'io.github.quentinpn.viewer\session.json'
[ordered]@{
  out = $out
  credentialEntries = @(cmdkey /list | Select-String 'Viewer').Count
  sessionJson = if (Test-Path $cfg) { Get-Content $cfg -Raw } else { "absent ($cfg)" }
} | ConvertTo-Json

