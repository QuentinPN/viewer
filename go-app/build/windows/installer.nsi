; NSIS installer of the Wails build of Viewer (unit "installer").
;
; It installs over a Tauri install: same per-user directory, same uninstall key, same
; shortcuts, same AppUserModelId, same command-line flags (/S /P /NS /UPDATE /R /ARGS).
; Derived from the installer.nsi that Tauri 2 generates for src-tauri/tauri.conf.json,
; with the nsis_tauri_utils plugin removed and the reinstall page dropped (see MAP.md).
;
; Build with scripts/build-wails-installer.ps1, which passes these defines:
;   VERSION, VERSIONWITHBUILD, OUTFILE, MAINBINARYSRCPATH, LIBMPVSRCPATH, INSTALLERICON
; Compile with `makensis /INPUTCHARSET UTF8`.

Unicode true
ManifestDPIAware true
ManifestDPIAwareness PerMonitorV2

SetCompressor /SOLID lzma

!ifndef VERSION
  !error "Pass /DVERSION=x.y.z"
!endif
!ifndef VERSIONWITHBUILD
  !error "Pass /DVERSIONWITHBUILD=x.y.z.0"
!endif
!ifndef OUTFILE
  !error "Pass /DOUTFILE=..."
!endif
!ifndef MAINBINARYSRCPATH
  !error "Pass /DMAINBINARYSRCPATH=...\viewer.exe"
!endif
!ifndef LIBMPVSRCPATH
  !error "Pass /DLIBMPVSRCPATH=...\libmpv-2.dll"
!endif

!include MUI2.nsh
!include FileFunc.nsh
!include x64.nsh
!include nsDialogs.nsh
!include "Win\COM.nsh"
!include "Win\Propkey.nsh"
!include "Win\RestartManager.nsh"
!include "utils.nsh"

; Same values as tauri.conf.json (productName, identifier) and the Tauri installer.
!define WEBVIEW2APPGUID "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
!define MANUFACTURER "QuentinPN"
!define PRODUCTNAME "Viewer"
!define MAINBINARYNAME "viewer"
!define BUNDLEID "io.github.quentinpn.viewer"
!define WEBVIEW2INSTALLERARGS "/silent"
!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCTNAME}"
!define MANUKEY "Software\${MANUFACTURER}"
!define MANUPRODUCTKEY "${MANUKEY}\${PRODUCTNAME}"

Var PassiveMode
Var UpdateMode
Var NoShortcutMode

Name "${PRODUCTNAME}"
BrandingText " "
OutFile "${OUTFILE}"

; Not the real default: it lets .onInit tell "no /D= given" from "given".
!define PLACEHOLDER_INSTALL_DIR "placeholder\${PRODUCTNAME}"
InstallDir "${PLACEHOLDER_INSTALL_DIR}"

VIProductVersion "${VERSIONWITHBUILD}"
VIAddVersionKey "ProductName" "${PRODUCTNAME}"
VIAddVersionKey "FileDescription" "${PRODUCTNAME}"
VIAddVersionKey "LegalCopyright" ""
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"

; installMode "currentUser": no administrator rights.
RequestExecutionLevel user

!ifdef INSTALLERICON
  !define MUI_ICON "${INSTALLERICON}"
  !define MUI_UNICON "${INSTALLERICON}"
!endif

; Where the installer language is remembered (same key as Tauri).
!define MUI_LANGDLL_REGISTRY_ROOT "HKCU"
!define MUI_LANGDLL_REGISTRY_KEY "${MANUPRODUCTKEY}"
!define MUI_LANGDLL_REGISTRY_VALUENAME "Installer Language"

; Installer pages
!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipIfPassive
!insertmacro MUI_PAGE_WELCOME

!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipIfPassive
!insertmacro MUI_PAGE_DIRECTORY

!insertmacro MUI_PAGE_INSTFILES

; Keep the install log visible: it helps to debug an installer failure.
!define MUI_FINISHPAGE_NOAUTOCLOSE
; The "show readme" check box creates the desktop shortcut.
!define MUI_FINISHPAGE_SHOWREADME
!define MUI_FINISHPAGE_SHOWREADME_TEXT "$(createDesktop)"
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION CreateOrUpdateDesktopShortcut
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION RunMainBinary
!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipIfPassive
!insertmacro MUI_PAGE_FINISH

Function RunMainBinary
  ; The installer is not elevated (RequestExecutionLevel user), so the app starts as the user.
  Exec '"$INSTDIR\${MAINBINARYNAME}.exe"'
FunctionEnd

; Uninstaller pages
Var DeleteAppDataCheckbox
Var DeleteAppDataCheckboxState
!define /ifndef WS_EX_LAYOUTRTL         0x00400000
!define MUI_PAGE_CUSTOMFUNCTION_SHOW un.ConfirmShow
Function un.ConfirmShow ; adds a "Delete app data" check box
  ; $1 inner dialog HWND, $2 window DPI, $3 style, $4 x, $5 y, $6 width, $7 height
  FindWindow $1 "#32770" "" $HWNDPARENT
  System::Call "user32::GetDpiForWindow(p r1) i .r2"
  ${If} $(^RTL) = 1
    StrCpy $3 "${__NSD_CheckBox_EXSTYLE} | ${WS_EX_LAYOUTRTL}"
    IntOp $4 50 * $2
  ${Else}
    StrCpy $3 "${__NSD_CheckBox_EXSTYLE}"
    IntOp $4 0 * $2
  ${EndIf}
  IntOp $5 100 * $2
  IntOp $6 400 * $2
  IntOp $7 25 * $2
  IntOp $4 $4 / 96
  IntOp $5 $5 / 96
  IntOp $6 $6 / 96
  IntOp $7 $7 / 96
  System::Call 'user32::CreateWindowEx(i r3, w "${__NSD_CheckBox_CLASS}", w "$(deleteAppData)", i ${__NSD_CheckBox_STYLE}, i r4, i r5, i r6, i r7, p r1, i0, i0, i0) i .s'
  Pop $DeleteAppDataCheckbox
  SendMessage $HWNDPARENT ${WM_GETFONT} 0 0 $1
  SendMessage $DeleteAppDataCheckbox ${WM_SETFONT} $1 1
FunctionEnd
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE un.ConfirmLeave
Function un.ConfirmLeave
  SendMessage $DeleteAppDataCheckbox ${BM_GETCHECK} 0 0 $DeleteAppDataCheckboxState
FunctionEnd
!define MUI_PAGE_CUSTOMFUNCTION_PRE un.SkipIfPassive
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; Language: French only, no selector (displayLanguageSelector false in Tauri).
!insertmacro MUI_LANGUAGE "French"
!insertmacro MUI_RESERVEFILE_LANGDLL

LangString appRunning ${LANG_FRENCH} "${PRODUCTNAME} est en cours d'exécution. Veuillez fermer l'application avant de réessayer."
LangString appRunningOkKill ${LANG_FRENCH} "${PRODUCTNAME} est en cours d'exécution.$\nCliquez sur OK pour fermer l'application."
LangString failedToKillApp ${LANG_FRENCH} "La fermeture de ${PRODUCTNAME} a échoué. Veuillez fermer l'application et réessayer."
LangString createDesktop ${LANG_FRENCH} "Créer un raccourci sur le bureau."
LangString deleteAppData ${LANG_FRENCH} "Supprimer les données de l'application"
LangString installingWebview2 ${LANG_FRENCH} "Installation de WebView2..."
LangString webview2AbortError ${LANG_FRENCH} "L'installation de WebView2 a échoué ! L'application ne peut s'exécuter sans WebView2. Veuillez essayer de redémarrer l'installation."
LangString webview2DownloadError ${LANG_FRENCH} "Erreur : le téléchargement de WebView2 a échoué - $0"
LangString webview2DownloadSuccess ${LANG_FRENCH} "Le composant WebView2 a été téléchargé avec succès !"
LangString webview2Downloading ${LANG_FRENCH} "Téléchargement du composant WebView2..."
LangString webview2InstallError ${LANG_FRENCH} "Erreur : l'installation de WebView2 a échoué avec le code d'erreur $1"
LangString webview2InstallSuccess ${LANG_FRENCH} "L'installation de WebView2 a réussi"

Function .onInit
  ${GetOptions} $CMDLINE "/P" $PassiveMode
  ${IfNot} ${Errors}
    StrCpy $PassiveMode 1
  ${EndIf}

  ${GetOptions} $CMDLINE "/NS" $NoShortcutMode
  ${IfNot} ${Errors}
    StrCpy $NoShortcutMode 1
  ${EndIf}

  ${GetOptions} $CMDLINE "/UPDATE" $UpdateMode
  ${IfNot} ${Errors}
    StrCpy $UpdateMode 1
  ${EndIf}

  !insertmacro SetContext

  ${If} $INSTDIR == "${PLACEHOLDER_INSTALL_DIR}"
    ; Default location: the one of the Tauri installer (installMode currentUser)
    StrCpy $INSTDIR "$LOCALAPPDATA\${PRODUCTNAME}"
    ; ... or the one of the previous install, when there is one
    Call RestorePreviousInstallLocation
  ${EndIf}
FunctionEnd

Section WebView2
  ; Skip when WebView2 is already installed (same registry probes as Tauri)
  ${If} ${RunningX64}
    ReadRegStr $4 HKLM "SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\${WEBVIEW2APPGUID}" "pv"
  ${Else}
    ReadRegStr $4 HKLM "SOFTWARE\Microsoft\EdgeUpdate\Clients\${WEBVIEW2APPGUID}" "pv"
  ${EndIf}
  ${If} $4 == ""
    ReadRegStr $4 HKCU "SOFTWARE\Microsoft\EdgeUpdate\Clients\${WEBVIEW2APPGUID}" "pv"
  ${EndIf}

  ${If} $4 == ""
    ; An update never installs WebView2: the app already ran.
    ${If} $UpdateMode <> 1
      ; installWebview2Mode "downloadBootstrapper", the Tauri default.
      Delete "$TEMP\MicrosoftEdgeWebview2Setup.exe"
      DetailPrint "$(webview2Downloading)"
      NSISdl::download "https://go.microsoft.com/fwlink/p/?LinkId=2124703" "$TEMP\MicrosoftEdgeWebview2Setup.exe"
      Pop $0
      ${If} $0 == "success"
        DetailPrint "$(webview2DownloadSuccess)"
      ${Else}
        DetailPrint "$(webview2DownloadError)"
        Abort "$(webview2AbortError)"
      ${EndIf}

      DetailPrint "$(installingWebview2)"
      ExecWait "$TEMP\MicrosoftEdgeWebview2Setup.exe ${WEBVIEW2INSTALLERARGS} /install" $1
      ${If} $1 = 0
        DetailPrint "$(webview2InstallSuccess)"
      ${Else}
        DetailPrint "$(webview2InstallError)"
        Abort "$(webview2AbortError)"
      ${EndIf}
    ${EndIf}
  ${EndIf}
SectionEnd

Section Install
  SetOutPath $INSTDIR

  ; Close a running Viewer before overwriting its exe (Tauri or Wails build)
  !insertmacro CheckIfAppIsRunning "$INSTDIR\${MAINBINARYNAME}.exe"

  File "${MAINBINARYSRCPATH}"
  ; libmpv-2.dll next to the exe: the production build loads it from there only
  File /a "/oname=libmpv-2.dll" "${LIBMPVSRCPATH}"

  WriteUninstaller "$INSTDIR\uninstall.exe"

  ; Remember $INSTDIR for the next installs (the Tauri installer reads this key too)
  WriteRegStr HKCU "${MANUPRODUCTKEY}" "" $INSTDIR

  ; Registry information for Add/Remove programs: the key and values of the Tauri installer,
  ; so this install replaces the Tauri one
  WriteRegStr HKCU "${UNINSTKEY}" "MainBinaryName" "${MAINBINARYNAME}.exe"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayName" "${PRODUCTNAME}"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayIcon" "$\"$INSTDIR\${MAINBINARYNAME}.exe$\""
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINSTKEY}" "Publisher" "${MANUFACTURER}"
  WriteRegStr HKCU "${UNINSTKEY}" "InstallLocation" "$\"$INSTDIR$\""
  WriteRegStr HKCU "${UNINSTKEY}" "UninstallString" "$\"$INSTDIR\uninstall.exe$\""
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoRepair" 1

  ${GetSize} "$INSTDIR" "/M=uninstall.exe /S=0K /G=0" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKCU "${UNINSTKEY}" "EstimatedSize" "$0"

  ; Start menu shortcut
  Call CreateOrUpdateStartMenuShortcut

  ; The finish page is skipped in silent and passive installs, so create the desktop shortcut here
  ${If} $PassiveMode = 1
  ${OrIf} ${Silent}
    Call CreateOrUpdateDesktopShortcut
  ${EndIf}

  ${If} $PassiveMode = 1
    SetAutoClose true
  ${EndIf}
SectionEnd

Function .onInstSuccess
  ; /R only counts in silent and passive installs: the GUI has a "run" check box.
  ; /ARGS is followed by the arguments to pass to the app.
  ${If} $PassiveMode = 1
  ${OrIf} ${Silent}
    ${GetOptions} $CMDLINE "/R" $R0
    ${IfNot} ${Errors}
      ${GetOptions} $CMDLINE "/ARGS" $R0
      Exec '"$INSTDIR\${MAINBINARYNAME}.exe" $R0'
    ${EndIf}
  ${EndIf}
FunctionEnd

Function un.onInit
  !insertmacro SetContext

  !insertmacro MUI_UNGETLANGUAGE

  ${GetOptions} $CMDLINE "/P" $PassiveMode
  ${IfNot} ${Errors}
    StrCpy $PassiveMode 1
  ${EndIf}

  ${GetOptions} $CMDLINE "/UPDATE" $UpdateMode
  ${IfNot} ${Errors}
    StrCpy $UpdateMode 1
  ${EndIf}
FunctionEnd

Section Uninstall
  !insertmacro CheckIfAppIsRunning "$INSTDIR\${MAINBINARYNAME}.exe"

  ; Delete what the installer wrote, and nothing else
  Delete "$INSTDIR\${MAINBINARYNAME}.exe"
  Delete "$INSTDIR\libmpv-2.dll"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"

  ; Remove the shortcuts unless updating, and only the ones that point at this install
  ${If} $UpdateMode <> 1
    !insertmacro DeleteAppUserModelId

    !insertmacro IsShortcutTarget "$SMPROGRAMS\${PRODUCTNAME}.lnk" "$INSTDIR\${MAINBINARYNAME}.exe"
    Pop $0
    ${If} $0 = 1
      !insertmacro UnpinShortcut "$SMPROGRAMS\${PRODUCTNAME}.lnk"
      Delete "$SMPROGRAMS\${PRODUCTNAME}.lnk"
    ${EndIf}

    !insertmacro IsShortcutTarget "$DESKTOP\${PRODUCTNAME}.lnk" "$INSTDIR\${MAINBINARYNAME}.exe"
    Pop $0
    ${If} $0 = 1
      !insertmacro UnpinShortcut "$DESKTOP\${PRODUCTNAME}.lnk"
      Delete "$DESKTOP\${PRODUCTNAME}.lnk"
    ${EndIf}
  ${EndIf}

  DeleteRegKey HKCU "${UNINSTKEY}"

  ; Not updating: the app must not start on login once it is gone
  ${If} $UpdateMode <> 1
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${PRODUCTNAME}"
  ${EndIf}

  ; The app data goes only if the check box is ticked, and never on an update
  ${If} $DeleteAppDataCheckboxState = 1
  ${AndIf} $UpdateMode <> 1
    DeleteRegKey HKCU "${MANUPRODUCTKEY}"
    DeleteRegKey /ifempty HKCU "${MANUKEY}"
    DeleteRegValue HKCU "${MANUPRODUCTKEY}" "Installer Language"
    DeleteRegKey /ifempty HKCU "${MANUPRODUCTKEY}"
    DeleteRegKey /ifempty HKCU "${MANUKEY}"

    SetShellVarContext current
    RmDir /r "$APPDATA\${BUNDLEID}"
    RmDir /r "$LOCALAPPDATA\${BUNDLEID}"
  ${EndIf}

  ${If} $PassiveMode = 1
  ${OrIf} $UpdateMode = 1
    SetAutoClose true
  ${EndIf}
SectionEnd

Function RestorePreviousInstallLocation
  ReadRegStr $4 HKCU "${MANUPRODUCTKEY}" ""
  StrCmp $4 "" +2 0
    StrCpy $INSTDIR $4
FunctionEnd

Function SkipIfPassive
  ${IfThen} $PassiveMode = 1 ${|} Abort ${|}
FunctionEnd
Function un.SkipIfPassive
  ${IfThen} $PassiveMode = 1 ${|} Abort ${|}
FunctionEnd

Function CreateOrUpdateStartMenuShortcut
  ; Skip in update mode or with /NS, so a shortcut the user deleted stays deleted
  ${If} $UpdateMode = 1
  ${OrIf} $NoShortcutMode = 1
    Return
  ${EndIf}

  CreateShortcut "$SMPROGRAMS\${PRODUCTNAME}.lnk" "$INSTDIR\${MAINBINARYNAME}.exe"
  !insertmacro SetLnkAppUserModelId "$SMPROGRAMS\${PRODUCTNAME}.lnk"
FunctionEnd

Function CreateOrUpdateDesktopShortcut
  ${If} $UpdateMode = 1
  ${OrIf} $NoShortcutMode = 1
    Return
  ${EndIf}

  CreateShortcut "$DESKTOP\${PRODUCTNAME}.lnk" "$INSTDIR\${MAINBINARYNAME}.exe"
  !insertmacro SetLnkAppUserModelId "$DESKTOP\${PRODUCTNAME}.lnk"
FunctionEnd
