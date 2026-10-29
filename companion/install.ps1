param([switch]$Launch, [string]$Pythonw)
$ErrorActionPreference = 'Stop'
$taskDestination = Join-Path $env:LOCALAPPDATA 'ManhwaTakipCompanion'
$taskSource = [IO.Path]::GetFullPath($PSScriptRoot)
if ($taskDestination -eq $taskSource) { throw 'Kaynak ve kurulum klasörü aynı olamaz.' }
$taskPython = if ($Pythonw) { (Resolve-Path -LiteralPath $Pythonw -ErrorAction Stop).Path } else { (Get-Command pythonw.exe -ErrorAction Stop).Source }
$taskConsolePython = Join-Path (Split-Path $taskPython) 'python.exe'
# Explicit runtime preflight, never an automatic dependency installation.
& $taskConsolePython -c "import importlib.metadata as m; import pystray; from PIL import Image; assert m.version('Pillow') == '13.0.0'; assert m.version('pystray') == '0.19.5'; assert m.version('six') == '1.17.0'"
if ($LASTEXITCODE -ne 0) { throw 'Seçilen Python ortamında requirements-runtime.txt bağımlılıkları eksik veya farklı. Önce açıkça bu ortama kurun, sonra -Pythonw ile tekrar deneyin. Veriler değiştirilmedi.' }
New-Item -ItemType Directory -Path $taskDestination -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $taskDestination 'bridge') -Force | Out-Null
$taskExecutable = Join-Path $taskDestination 'ManhwaTakip.exe'
& (Join-Path $taskSource 'build-launcher.ps1') -OutputPath $taskExecutable -Pythonw $taskPython -ApplicationFolder $taskDestination
# Preserve legacy local launch settings before replacing an installed launcher. No credentials are read.
$taskBridgeSource = Join-Path (Split-Path $taskSource) 'bridge'
$taskLegacyLauncher = Join-Path $taskDestination 'bridge/start_connections.py'
& $taskConsolePython -c "import sys; sys.path.insert(0, sys.argv[1]); from start_connections import migrate_private; migrate_private(sys.argv[2])" $taskBridgeSource $taskLegacyLauncher
if ($LASTEXITCODE -ne 0) { throw 'Özel bağlantı ayarı korunamadı; kurulum durduruldu.' }
@('app.py','core.py','covers.py','image_decode.py','connections.py','preferences.py','theme.py','tray.py','requirements-runtime.txt','test_companion.py') | ForEach-Object { Copy-Item -LiteralPath (Join-Path $taskSource $_) -Destination (Join-Path $taskDestination $_) -Force }
New-Item -ItemType Directory -Path (Join-Path $taskDestination 'licenses') -Force | Out-Null
@('Pillow-LICENSE.txt','pystray-COPYING.txt','pystray-COPYING.LGPL.txt','six-LICENSE.txt') | ForEach-Object {
    Copy-Item -LiteralPath (Join-Path $taskSource ('licenses/' + $_)) -Destination (Join-Path $taskDestination ('licenses/' + $_)) -Force
}
Copy-Item -LiteralPath (Join-Path (Split-Path $taskSource) 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $taskDestination 'THIRD_PARTY_NOTICES.md') -Force
# Deliberate allowlist: do not ship tests, personal config, keys or the whole bridge folder.
@('bridge.py','start_connections.py','user_setup.py') | ForEach-Object {
    Copy-Item -LiteralPath (Join-Path $taskBridgeSource $_) -Destination (Join-Path $taskDestination ('bridge/' + $_)) -Force
}
$taskShell = New-Object -ComObject WScript.Shell
$taskDesktop = [Environment]::GetFolderPath('Desktop')
$taskDesktopExe = Join-Path $taskDesktop 'Manhwa Takip.exe'
Copy-Item -LiteralPath $taskExecutable -Destination $taskDesktopExe -Force
$taskShortcutPath = Join-Path $taskDesktop 'Manhwa Takip Bilgisayar.lnk'
if (Test-Path -LiteralPath $taskShortcutPath) {
    $taskShortcut = $taskShell.CreateShortcut($taskShortcutPath)
    $taskShortcut.TargetPath = $taskExecutable
    $taskShortcut.Arguments = ''
    $taskShortcut.WorkingDirectory = $taskDestination
    $taskShortcut.Description = 'Manhwa Takip USB kütüphane, düzenleme ve yedekleme'
    $taskShortcut.Save()
}
Write-Output ('COMPANION_INSTALLED=' + $taskDestination)
Write-Output ('DESKTOP_EXE=' + $taskDesktopExe)
if ($Launch) { Start-Process -FilePath $taskExecutable -WorkingDirectory $taskDestination -WindowStyle Hidden }
