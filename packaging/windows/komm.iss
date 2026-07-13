; Inno Setup script for the Komm installer.
; Compiled by the launcher's `installer` Maven profile, which first builds the
; jpackage app-image (launcher exe + private runtime + bundled client seed) and
; then passes these defines:
;   /DAppVersion=...    client version bundled as the seed (drives the setup filename)
;   /DAppImageDir=...   the jpackage app-image directory to package
;   /DOutputDir=...     where to write the setup exe

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef AppImageDir
  #define AppImageDir "..\..\target\jpackage\Komm"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\target\installer"
#endif

[Setup]
; Never change AppId: it is how upgrades find and replace an existing install.
AppId={{5EE5B212-EE3B-43E1-8657-5C183E3FDE55}
AppName=Komm
AppVersion={#AppVersion}
AppVerName=Komm {#AppVersion}
AppPublisher=Komm
VersionInfoVersion={#AppVersion}
; Per-user install: no admin prompt, matches the HKCU komm:// registration.
PrivilegesRequired=lowest
DefaultDirName={autopf}\Komm
DefaultGroupName=Komm
; Fresh installs may choose the folder; upgrades silently reuse the existing one.
DisableDirPage=auto
DisableProgramGroupPage=yes
DisableWelcomePage=no
WizardStyle=modern
WizardImageFile=wizard-large.bmp
WizardSmallImageFile=wizard-small.bmp
SetupIconFile=komm.ico
UninstallDisplayIcon={app}\Komm.exe
UninstallDisplayName=Komm
OutputDir={#OutputDir}
OutputBaseFilename=Komm-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
CloseApplications=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "italian"; MessagesFile: "compiler:Languages\Italian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Always drop the installed client jar so every (re)install starts from this
; installer's bundled seed — BundledClientSeeder re-copies it on first launch,
; and the hub update flow takes over from there. Doubling as a repair path:
; reinstalling fixes a corrupt jar. Partial downloads, the legacy version.txt
; and extracted JNativeHook natives (re-extracted by the client on next start)
; are cleaned up alongside it.
Type: files; Name: "{userappdata}\Komm\bin\komm.jar"
Type: files; Name: "{userappdata}\Komm\bin\komm.jar.download"
Type: files; Name: "{userappdata}\Komm\bin\version.txt"
Type: files; Name: "{userappdata}\Komm\bin\JNativeHook-*.dll"

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Komm"; Filename: "{app}\Komm.exe"
Name: "{autodesktop}\Komm"; Filename: "{app}\Komm.exe"; Tasks: desktopicon

[Registry]
; komm:// URL protocol so browser invite links ("Open in Komm app") start the
; launcher. The launcher also re-registers itself on every start, so this mainly
; guarantees the handler exists before the first run.
Root: HKA; Subkey: "Software\Classes\komm"; ValueType: string; ValueData: "URL:Komm Protocol"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\komm"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\komm\DefaultIcon"; ValueType: string; ValueData: """{app}\Komm.exe"",0"
Root: HKA; Subkey: "Software\Classes\komm\shell\open\command"; ValueType: string; ValueData: """{app}\Komm.exe"" ""%1"""

[Run]
Filename: "{app}\Komm.exe"; Description: "{cm:LaunchProgram,Komm}"; Flags: nowait postinstall skipifsilent
