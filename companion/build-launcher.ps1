param([Parameter(Mandatory=$true)][string]$OutputPath, [Parameter(Mandatory=$true)][string]$Pythonw,
      [string]$ApplicationFolder = (Join-Path $env:LOCALAPPDATA 'ManhwaTakipCompanion'))
$ErrorActionPreference = 'Stop'
$taskCompiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $taskCompiler)) { throw 'Windows .NET Framework derleyicisi bulunamadı.' }
if (-not (Test-Path -LiteralPath $Pythonw)) { throw 'Python çalıştırıcısı bulunamadı.' }
$taskOutput = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
$taskSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Launcher.cs')).Replace('__PYTHONW_PATH__', $Pythonw.Replace('"','""')).Replace('__APPLICATION_FOLDER__', ([IO.Path]::GetFullPath($ApplicationFolder)).Replace('"','""'))
$taskTemporary = Join-Path ([IO.Path]::GetDirectoryName($taskOutput)) ([IO.Path]::GetRandomFileName() + '.cs')
try {
    [IO.File]::WriteAllText($taskTemporary, $taskSource, (New-Object Text.UTF8Encoding($true)))
    & $taskCompiler /nologo /target:winexe /optimize+ /reference:System.Windows.Forms.dll ('/out:' + $taskOutput) $taskTemporary
    if ($LASTEXITCODE -ne 0) { throw 'EXE başlatıcısı derlenemedi.' }
} finally { if (Test-Path -LiteralPath $taskTemporary) { Remove-Item -LiteralPath $taskTemporary } }
