# Runs a command with the Emby test account in VIEWER_EMBY_USER / VIEWER_EMBY_PASSWORD,
# for that command only. The account is a generic credential of the Windows
# Credential Manager, created once by the user (cmdkey prompts for the password,
# hidden):
#   cmdkey /generic:viewer-e2e-emby /user:<test user> /pass
# VIEWER_EMBY_URL keeps its value when set, otherwise it is the last server the debug
# app signed in to, else the installed app's (session.json, no secret in it; the
# debug build has its own data and vault entries).
#
#   ./scripts/with-test-account.ps1 node scripts/e2e/gate2-ui.mjs
$ErrorActionPreference = 'Stop'
if ($args.Count -eq 0) { throw 'usage: with-test-account.ps1 <command> [args…]' }
$Target = 'viewer-e2e-emby'
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class E2eCred {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  struct CREDENTIAL {
    public uint Flags, Type; public string TargetName, Comment;
    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
    public uint CredentialBlobSize; public IntPtr CredentialBlob;
    public uint Persist, AttributeCount; public IntPtr Attributes; public string TargetAlias, UserName;
  }
  [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  static extern bool CredReadW(string target, uint type, uint flags, out IntPtr cred);
  [DllImport("advapi32.dll")] static extern void CredFree(IntPtr cred);
  public static string[] Read(string target) {
    IntPtr p;
    if (!CredReadW(target, 1, 0, out p)) return null;
    try {
      var c = (CREDENTIAL)Marshal.PtrToStructure(p, typeof(CREDENTIAL));
      return new[] { c.UserName, Marshal.PtrToStringUni(c.CredentialBlob, (int)c.CredentialBlobSize / 2) };
    } finally { CredFree(p); }
  }
}
'@
$cred = [E2eCred]::Read($Target)
if (-not $cred) { throw "No '$Target' credential: run  cmdkey /generic:$Target /user:<test user> /pass" }
$hadUrl = [bool]$env:VIEWER_EMBY_URL
if (-not $hadUrl) {
  foreach ($app in 'io.github.quentinpn.viewer.debug', 'io.github.quentinpn.viewer') {
    $session = Join-Path $env:APPDATA "$app\session.json"
    if (Test-Path $session) {
      $env:VIEWER_EMBY_URL = (Get-Content $session -Raw | ConvertFrom-Json).lastServerUrl
      if ($env:VIEWER_EMBY_URL) { break }
    }
  }
}
$env:VIEWER_EMBY_USER = $cred[0]
$env:VIEWER_EMBY_PASSWORD = $cred[1]
try {
  # An array even with one argument: splatting a string passes its characters.
  $rest = @($args | Select-Object -Skip 1)
  & $args[0] @rest
  exit $LASTEXITCODE
} finally {
  Remove-Item Env:VIEWER_EMBY_USER, Env:VIEWER_EMBY_PASSWORD -ErrorAction SilentlyContinue
  if (-not $hadUrl) { Remove-Item Env:VIEWER_EMBY_URL -ErrorAction SilentlyContinue }
}
