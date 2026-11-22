# Runs a CDP UI scenario (scripts/e2e/<Scenario>.mjs) against the Go + Wails debug
# build, go-app/bin/viewer-debug.exe. -Build makes it first (npm run build, then a
# debug go build; app.mjs buildDebug). The window opens off-screen and is driven over
# CDP only, never by OS input: the desktop stays usable during the run.
#
# The Emby test account comes from the Windows Credential Manager through
# scripts/with-test-account.ps1: it reaches the scenario process only, never an
# argument or a file. VIEWER_EMBY_URL (optional) overrides the server address.
param([string]$Scenario = 'gate2-ui', [switch]$Build)
$ErrorActionPreference = 'Stop'
if ($Build) {
  node (Join-Path $PSScriptRoot 'app.mjs') --build
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& (Join-Path $PSScriptRoot '..\with-test-account.ps1') node (Join-Path $PSScriptRoot "$Scenario.mjs")
exit $LASTEXITCODE
