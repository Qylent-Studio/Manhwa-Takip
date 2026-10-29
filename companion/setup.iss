#ifndef PayloadDir
  #error PayloadDir must name the scanned payload
#endif
#ifndef OutputDir
  #error OutputDir required
#endif
[Setup]
AppId={{82E373D8-135A-40DE-95E7-017E01DE4392}
AppName=Manhwa Takip
AppVersion=0.10.0
AppPublisher=Qylent Studio
DefaultDirName={localappdata}\Programs\ManhwaTakip
DefaultGroupName=Manhwa Takip
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\Manhwa Takip.exe
CloseApplications=yes
RestartApplications=no
SetupLogging=no
LicenseFile={#PayloadDir}\LICENSE

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Manhwa Takip"; Filename: "{app}\Manhwa Takip.exe"
Name: "{userdesktop}\Manhwa Takip"; Filename: "{app}\Manhwa Takip.exe"
