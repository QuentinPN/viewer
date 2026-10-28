# Runs a CDP UI scenario (scripts/e2e/<Scenario>.mjs) against the debug build. Needs
# `cargo build` and the Vite dev server (npm run dev). The window opens off-screen and
# is never activated: the desktop stays usable during the run.
#
# The Emby test account comes from the Windows Credential Manager through
# scripts/with-test-account.ps1: it reaches the scenario process only, never an
# argument or a file. VIEWER_EMBY_URL (optional) overrides the server address.
param([string]$Scenario = 'gate2-ui')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot '..\with-test-account.ps1') node (Join-Path $PSScriptRoot "$Scenario.mjs")
exit $LASTEXITCODE
