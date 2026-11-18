Unicode True
!include "LogicLib.nsh"
!include "x64.nsh"
!include "WinVer.nsh"

Name "Utena Mic"
Icon "..\assets\logo.ico"
OutFile "${OUTPUT}"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
InstallDir "$PROGRAMFILES64\Utena Mic"
ShowInstDetails show
Page instfiles
UninstPage uninstConfirm
UninstPage instfiles

!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\UtenaMic"
!define TCP_RULE "Utena Mic audio (TCP 48395)"
!define UDP_RULE "Utena Mic discovery (UDP 48395)"

Function .onInit
    StrCpy $INSTDIR "$PROGRAMFILES64\Utena Mic"
    ${If} ${AtLeastBuild} 19045
    ${AndIf} ${IsNativeAMD64}
        Return
    ${EndIf}
    ${If} ${AtLeastBuild} 19045
    ${AndIf} ${IsNativeARM64}
        Return
    ${EndIf}
    MessageBox MB_ICONSTOP "Utena Mic requires Windows 10 version 22H2 or Windows 11 on an x64 or Arm64 PC." /SD IDOK
    Abort
FunctionEnd

Section "Utena Mic"
    SetShellVarContext all
    SetRegView 64
    SetOutPath "$INSTDIR"
    StrCpy $1 0
    ${Do}
        FindWindow $0 "" "Utena Mic"
        ${If} $0 == 0
            ${ExitDo}
        ${EndIf}
        Sleep 200
        IntOp $1 $1 + 1
        ${If} $1 >= 25
            Abort "Close Utena Mic and run the installer again."
        ${EndIf}
    ${Loop}
    ${If} ${IsNativeARM64}
        File /r "${PAYLOAD}\win-arm64\*"
    ${Else}
        File /r "${PAYLOAD}\win-x64\*"
    ${EndIf}

    ClearErrors
    ExecWait '"$INSTDIR\UtenaMic.exe" --vac-installed' $2
    ${If} ${Errors}
        Abort "Utena Mic could not start."
    ${EndIf}
    ${If} $2 == 2
        IfSilent 0 +2
        Abort "Installing VAC Lite for the first time requires accepting its license in its installer."
        InitPluginsDir
        SetOutPath "$PLUGINSDIR"
        File /oname=vac471lite.zip "${VAC_ARCHIVE}"
        ClearErrors
        ExecWait '"$INSTDIR\UtenaMic.exe" --save-audio-defaults "$PLUGINSDIR\audio-defaults.txt"' $1
        ${If} ${Errors}
        ${OrIf} $1 != 0
            Abort "Could not save the current default audio devices."
        ${EndIf}
        ClearErrors
        ExecWait '"$WINDIR\Sysnative\tar.exe" -xf "$PLUGINSDIR\vac471lite.zip" -C "$PLUGINSDIR"' $0
        ${If} ${Errors}
        ${OrIf} $0 != 0
            Abort "Could not extract the original VAC Lite installer."
        ${EndIf}

        DetailPrint "Accept the VAC Lite license and complete its setup to install the virtual microphone."
        ClearErrors
        ${If} ${IsNativeARM64}
            ExecWait '"$PLUGINSDIR\setup64a.exe"' $2
        ${Else}
            ExecWait '"$PLUGINSDIR\setup64.exe"' $2
        ${EndIf}
        ${If} ${Errors}
            StrCpy $2 1
        ${EndIf}
        ClearErrors
        ExecWait '"$INSTDIR\UtenaMic.exe" --restore-audio-defaults "$PLUGINSDIR\audio-defaults.txt"' $1
        ${If} ${Errors}
        ${OrIf} $1 != 0
            MessageBox MB_ICONEXCLAMATION "Windows could not restore all default audio devices. Review your defaults in Settings > System > Sound." /SD IDOK
        ${EndIf}
        ${If} $2 != 0
            Abort "VAC Lite setup did not finish. Run Utena Mic Setup again to complete installation."
        ${EndIf}
    ${ElseIf} $2 != 0
        Abort "Could not check the VAC Lite audio devices."
    ${EndIf}

    SetOutPath "$INSTDIR"
    ClearErrors
    ExecWait '"$INSTDIR\UtenaMic.exe" --name-vac-capture' $1
    ${If} ${Errors}
    ${OrIf} $1 == 2
        Abort "The virtual microphone did not become available. If VAC Lite requested a restart, restart Windows and run Utena Mic Setup again."
    ${ElseIf} $1 != 0
        MessageBox MB_ICONEXCLAMATION "Windows kept the microphone name Line 1 (Virtual Audio Cable). Select that input in your Windows app." /SD IDOK
    ${EndIf}

    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="${TCP_RULE}" program="$INSTDIR\UtenaMic.exe"'
    Pop $0
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="${UDP_RULE}" program="$INSTDIR\UtenaMic.exe"'
    Pop $0
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="${TCP_RULE}" dir=in action=allow program="$INSTDIR\UtenaMic.exe" protocol=TCP localport=48395 remoteip=LocalSubnet profile=any'
    Pop $1
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="${UDP_RULE}" dir=in action=allow program="$INSTDIR\UtenaMic.exe" protocol=UDP localport=48395 remoteip=LocalSubnet profile=any'
    Pop $2
    ${If} $1 != 0
    ${OrIf} $2 != 0
        MessageBox MB_ICONEXCLAMATION "Windows Firewall could not be configured. Allow Utena Mic on your private network if Windows prompts you." /SD IDOK
    ${EndIf}

    WriteUninstaller "$INSTDIR\Uninstall.exe"
    CreateDirectory "$SMPROGRAMS\Utena Mic"
    CreateShortcut "$SMPROGRAMS\Utena Mic\Utena Mic.lnk" "$INSTDIR\UtenaMic.exe"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayName" "Utena Mic"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayVersion" "0.3.2"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
SectionEnd

Section "Uninstall"
    SetShellVarContext all
    SetRegView 64
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="${TCP_RULE}" program="$INSTDIR\UtenaMic.exe"'
    Pop $0
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="${UDP_RULE}" program="$INSTDIR\UtenaMic.exe"'
    Pop $0
    DeleteRegKey HKLM "${UNINSTALL_KEY}"
    Delete "$SMPROGRAMS\Utena Mic\Utena Mic.lnk"
    RMDir "$SMPROGRAMS\Utena Mic"
    ${If} ${IsNativeARM64}
        !include "${PAYLOAD}\win-arm64-uninstall.nsh"
    ${Else}
        !include "${PAYLOAD}\win-x64-uninstall.nsh"
    ${EndIf}
    Delete "$INSTDIR\Uninstall.exe"
    RMDir "$INSTDIR"
SectionEnd
