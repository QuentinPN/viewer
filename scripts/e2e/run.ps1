# Runs a CDP UI scenario (scripts/e2e/<Scenario>.mjs) against the debug build. Needs
# `cargo build` and the Vite dev server (npm run dev). The window opens off-screen and
# is never activated: the desktop stays usable during the run.
#
# The Emby test account is read from a generic credential of the Windows Credential
# Manager, created once by the user (cmdkey prompts for the password, hidden):
#   cmdkey /generic:viewer-e2e-emby /user:<test user> /pass
# It is passed to the scenario process only, never as an argument or in a file.
# VIEWER_EMBY_URL (optional) overrides the server address prefilled by the app.
param([string]$Scenario = 'gate2-ui')
$ErrorActionPreference = 'Stop'
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
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$env:VIEWER_EMBY_USER = $cred[0]
$env:VIEWER_EMBY_PASSWORD = $cred[1]
try {
  & node (Join-Path $PSScriptRoot "$Scenario.mjs")
  exit $LASTEXITCODE
} finally {
  Remove-Item Env:VIEWER_EMBY_USER, Env:VIEWER_EMBY_PASSWORD -ErrorAction SilentlyContinue
  Set-Location $root
}
