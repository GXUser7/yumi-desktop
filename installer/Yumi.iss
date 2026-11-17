#define AppVersion "1.0.4"
#define AppImage "..\desktop\build\compose\binaries\main\app\Yumi"
[Setup]
AppId={{E8CB03D0-C852-4375-BF1B-A83F09E118B7}
AppName=Yumi Desktop
AppVersion={#AppVersion}
AppPublisher=Yumi
DefaultDirName={autopf}\Yumi Desktop
DefaultGroupName=Yumi Desktop
DisableDirPage=yes
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
PrivilegesRequired=admin
OutputDir=..\dist
OutputBaseFilename=Yumi-Desktop-{#AppVersion}-windows-x64-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\Yumi.exe
LicenseFile=..\LICENSE
CloseApplications=yes
RestartApplications=no
SetupLogging=yes

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; Flags: unchecked

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\runtime\yumi-service.exe"; DestDir: "{app}\service"; Flags: ignoreversion
Source: "..\runtime\wintun.dll"; DestDir: "{app}\service"; Flags: ignoreversion
Source: "..\runtime\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\MANUAL-TESTS.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Yumi Desktop"; Filename: "{app}\Yumi.exe"
Name: "{autodesktop}\Yumi Desktop"; Filename: "{app}\Yumi.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Yumi.exe"; Description: "{cm:LaunchProgram,Yumi Desktop}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[Code]
function OpenProcess(Access: LongWord; Inherit: Boolean; Pid: LongWord): THandle;
  external 'OpenProcess@kernel32.dll stdcall';
function WaitForSingleObject(Handle: THandle; Milliseconds: LongWord): LongWord;
  external 'WaitForSingleObject@kernel32.dll stdcall';
function CloseHandle(Handle: THandle): Boolean;
  external 'CloseHandle@kernel32.dll stdcall';
function CreateFileW(FileName: String; DesiredAccess, ShareMode, SecurityAttributes, CreationDisposition, FlagsAndAttributes: LongWord; TemplateFile: THandle): THandle;
  external 'CreateFileW@kernel32.dll stdcall';

function InitializeSetup(): Boolean;
begin
  Result := True;
end;

function StopExistingService(): Boolean;
var
  ServiceExe: String;
  Code: Integer;
  Attempts: Integer;
  Handle: THandle;
begin
  Result := True;
  ServiceExe := ExpandConstant('{app}\service\yumi-service.exe');
  if not FileExists(ServiceExe) then Exit;
  if not Exec(ServiceExe, '--uninstall', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then begin
    Result := False;
    Exit;
  end;
  { Wait for the SCM service process to finish exiting and release its binary lock before
    overwriting or deleting files. }
  for Attempts := 1 to 50 do begin
    Handle := CreateFileW(ServiceExe, $40000000, 0, 0, 3, $80, 0);
    if (Handle <> 0) and (Handle <> $FFFFFFFF) then begin
      CloseHandle(Handle);
      Exit;
    end;
    Sleep(200);
  end;
  Result := False;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var Pid: Integer; Handle: THandle; WaitResult: LongWord;
begin
  Result := '';
  { An accepted update first lets the app disconnect and exit, releasing its JVM files. }
  if ExpandConstant('{param:YUMIUPDATE|0}') = '1' then begin
    Pid := StrToIntDef(ExpandConstant('{param:YUMIPID|0}'), 0);
    if Pid > 0 then begin
      Handle := OpenProcess($00100000, False, Pid);
      if Handle <> 0 then begin
        WaitResult := WaitForSingleObject(Handle, 30000);
        CloseHandle(Handle);
        if WaitResult <> 0 then begin
          Result := 'Close Yumi Desktop and retry the update.';
          Exit;
        end;
      end;
    end;
  end;
  { Stop only Yumi before replacing its service binary. Profiles live outside the installation. }
  if not StopExistingService() then
    Result := 'Could not stop the previous Yumi service. Close Yumi and retry.';
end;

{ The SID of the person who will actually run Yumi, which is not the same question as "who is
  running this installer". Setup is elevated, so its own token belongs to whoever answered the UAC
  prompt; on a machine where that is a separate administrator account, registering the service to
  it produces the worst kind of failure - the service runs, services.msc says so, and the app is
  refused by it with no explanation. ExecAsOriginalUser runs the service binary back under the
  logged-in user, which reports its own SID and nothing else. It needs no privilege and touches
  nothing else.

  The hand-off uses a file in Setup's private temp directory rather than ProgramData so unprivileged
  users cannot pre-stage or tamper with the file. Empty on failure, falling back to --install. }
function OriginalUserSid(): String;
var Code: Integer; Path: String; Raw: AnsiString; I: Integer; Ch: Char;
begin
  Result := '';
  Path := ExpandConstant('{tmp}\install-owner.txt');
  DeleteFile(Path);
  if ExecAsOriginalUser(ExpandConstant('{app}\service\yumi-service.exe'),
      '--owner-out "' + Path + '"', '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0) and
      LoadStringFromFile(Path, Raw) then
    Result := Trim(String(Raw));
  DeleteFile(Path);
  if (Length(Result) < 5) or (Length(Result) > 184) or (Copy(Result, 1, 2) <> 'S-') then begin
    Result := '';
    Exit;
  end;
  for I := 3 to Length(Result) do begin
    Ch := Result[I];
    if not (((Ch >= '0') and (Ch <= '9')) or (Ch = '-')) then begin Result := ''; Exit; end;
  end;
end;

function ShouldRelaunchAfterInstall(): Boolean;
begin
  Result := ExpandConstant('{param:YUMIRESTART|0}') = '1';
end;

procedure CurStepChanged(CurStep: TSetupStep);
var Code: Integer; Owner: String; Params: String;
begin
  if CurStep = ssPostInstall then begin
    Owner := OriginalUserSid();
    if Owner <> '' then Params := '--install --owner ' + Owner else Params := '--install';
    if not Exec(ExpandConstant('{app}\service\yumi-service.exe'), Params, '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
      RaiseException('Yumi tunnel service could not be installed. Please run the installer again.');
    { Pairing needs one inbound port while the QR screen is open, scoped to private local networks. }
    Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall delete rule name="Yumi Desktop Pairing"', '', SW_HIDE, ewWaitUntilTerminated, Code);
    if not Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall add rule name="Yumi Desktop Pairing" dir=in action=allow program="' + ExpandConstant('{app}\Yumi.exe') + '" enable=yes profile=private remoteip=localsubnet protocol=TCP', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
      RaiseException('Yumi was installed, but its local-network pairing rule could not be added.');
  end;
  if (CurStep = ssDone) and ShouldRelaunchAfterInstall() then
    ExecAsOriginalUser(ExpandConstant('{app}\Yumi.exe'), '', '', SW_SHOWNORMAL, ewNoWait, Code);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var Code: Integer;
begin
  if CurUninstallStep = usUninstall then begin
    if FileExists(ExpandConstant('{app}\service\yumi-service.exe')) then
      StopExistingService();
    Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall delete rule name="Yumi Desktop Pairing"', '', SW_HIDE, ewWaitUntilTerminated, Code);
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'YumiDesktop');
  end;
end;
