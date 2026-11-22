; Helpers of installer.nsi. Derived from the NSIS template of Tauri 2 (utils.nsh,
; MIT / Apache-2.0), with the nsis_tauri_utils plugin removed: the installer only
; needs the NSIS that ships with makensis.

; Change shell and registry context: per-user install, 64-bit registry view.
!macro SetContext
  SetShellVarContext current
  ${If} ${RunningX64}
    SetRegView 64
  ${EndIf}
!macroend

!define /ifndef ERROR_MORE_DATA 234

; Checks whether the app is running and closes it with the Restart Manager.
; Silent and passive installs close it without asking, as the Tauri installer does.
; Uses $0-$3 and $R0-$R3.
!macro CheckIfAppIsRunning executablePath
  !define UniqueID ${__LINE__}

  StrCpy $R1 "$(appRunning)"
  StrCpy $R2 "$(appRunningOkKill)"
  StrCpy $R3 "$(failedToKillApp)"

  !insertmacro RestartManager_StartSession $R0
  ${If} $R0 != ""
    !insertmacro RestartManager_RegisterFile $R0 "${executablePath}"
    ${If} $0 = 0
      System::Call 'RSTRTMGR::RmGetList(p R0, *i .r1, *i .r2, p 0, *i .r3) i .r0'
      ; ERROR_SUCCESS means no running processes, ERROR_MORE_DATA means there are some
      ${If} $0 = ${ERROR_MORE_DATA}
        IfSilent kill_${UniqueID} 0
        ${IfThen} $PassiveMode != 1 ${|} MessageBox MB_OKCANCEL $R2 IDOK kill_${UniqueID} IDCANCEL cancel_${UniqueID} ${|}
        kill_${UniqueID}:
          System::Call 'RSTRTMGR::RmShutdown(p R0, i ${RmForceShutdown}, p 0) i .r0'
          ${If} $0 = 0
            Goto app_check_done_${UniqueID}
          ${Else}
            IfSilent silent_${UniqueID} ui_${UniqueID}
            silent_${UniqueID}:
              System::Call 'kernel32::AttachConsole(i -1)i.r0'
              ${If} $0 != 0
                System::Call 'kernel32::GetStdHandle(i -11)i.r0'
                System::call 'kernel32::SetConsoleTextAttribute(i r0, i 0x0004)' ; red
                FileWrite $0 "$R1$\n"
              ${EndIf}
              !insertmacro RestartManager_EndSession $R0
              Abort
            ui_${UniqueID}:
              !insertmacro RestartManager_EndSession $R0
              Abort $R3
          ${EndIf}
        cancel_${UniqueID}:
          !insertmacro RestartManager_EndSession $R0
          Abort $R1
        app_check_done_${UniqueID}:
      ${EndIf}
    ${EndIf}
    !insertmacro RestartManager_EndSession $R0
  ${EndIf}

  !undef UniqueID
!macroend

; Sets the AppUserModelId (the bundle identifier) on a shortcut.
!macro SetLnkAppUserModelId shortcut
  !insertmacro ComHlpr_CreateInProcInstance ${CLSID_ShellLink} ${IID_IShellLink} r0 ""
  ${If} $0 P<> 0
    ${IUnknown::QueryInterface} $0 '("${IID_IPersistFile}",.r1)'
    ${If} $1 P<> 0
      ${IPersistFile::Load} $1 '("${shortcut}", ${STGM_READWRITE})'
      ${IUnknown::QueryInterface} $0 '("${IID_IPropertyStore}",.r2)'
      ${If} $2 P<> 0
        System::Call 'Oleaut32::SysAllocString(w "${BUNDLEID}") i.r3'
        System::Call '*${SYSSTRUCT_PROPERTYKEY}(${PKEY_AppUserModel_ID})p.r4'
        System::Call '*${SYSSTRUCT_PROPVARIANT}(${VT_BSTR},,&i4 $3)p.r5'
        ${IPropertyStore::SetValue} $2 '($4,$5)'

        System::Call 'Oleaut32::SysFreeString($3)'
        System::Free $4
        System::Free $5
        ${IPropertyStore::Commit} $2 ""
        ${IUnknown::Release} $2 ""
        ${IPersistFile::Save} $1 '("${shortcut}",1)'
      ${EndIf}
      ${IUnknown::Release} $1 ""
    ${EndIf}
    ${IUnknown::Release} $0 ""
  ${EndIf}
!macroend

; Deletes the jump list entries and recent destinations of the AppUserModelId.
!macro DeleteAppUserModelId
  !insertmacro ComHlpr_CreateInProcInstance ${CLSID_DestinationList} ${IID_ICustomDestinationList} r1 ""
  ${If} $1 P<> 0
    ${ICustomDestinationList::DeleteList} $1 '("${BUNDLEID}")'
    ${IUnknown::Release} $1 ""
  ${EndIf}
  !insertmacro ComHlpr_CreateInProcInstance ${CLSID_ApplicationDestinations} ${IID_IApplicationDestinations} r1 ""
  ${If} $1 P<> 0
    ${IApplicationDestinations::SetAppID} $1 '("${BUNDLEID}")i.r0'
    ${If} $0 >= 0
      ${IApplicationDestinations::RemoveAllDestinations} $1 ''
    ${EndIf}
    ${IUnknown::Release} $1 ""
  ${EndIf}
!macroend

; Unpins a shortcut from the Start menu and the taskbar.
; From https://stackoverflow.com/a/42816728/16993372
!macro UnpinShortcut shortcut
  !insertmacro ComHlpr_CreateInProcInstance ${CLSID_StartMenuPin} ${IID_IStartMenuPinnedList} r0 ""
  ${If} $0 P<> 0
      System::Call 'SHELL32::SHCreateItemFromParsingName(ws, p0, g "${IID_IShellItem}", *p0r1)' "${shortcut}"
      ${If} $1 P<> 0
          ${IStartMenuPinnedList::RemoveFromList} $0 '(r1)'
          ${IUnknown::Release} $1 ""
      ${EndIf}
      ${IUnknown::Release} $0 ""
  ${EndIf}
!macroend

!define /ifndef MAX_PATH 260
!define /ifndef SLGP_RAWPATH 0x4

; Tests whether a .lnk shortcut points at target; Pop gives 1 (yes) or 0 (no).
; Modifies $0, $1, $2, $3.
!macro IsShortcutTarget shortcut target
  StrCpy $3 0
  !insertmacro ComHlpr_CreateInProcInstance ${CLSID_ShellLink} ${IID_IShellLink} r0 ""
  ${If} $0 P<> 0
    ${IUnknown::QueryInterface} $0 '("${IID_IPersistFile}", .r1)'
    ${If} $1 P<> 0
      ${IPersistFile::Load} $1 '("${shortcut}", ${STGM_READ})'
      System::Alloc MAX_PATH
      Pop $2
      ${IShellLink::GetPath} $0 '(.r2, ${MAX_PATH}, 0, ${SLGP_RAWPATH})'
      ${If} $2 == "${target}"
        StrCpy $3 1
      ${EndIf}
      System::Free $2
      ${IUnknown::Release} $1 ""
    ${EndIf}
    ${IUnknown::Release} $0 ""
  ${EndIf}
  Push $3
!macroend
