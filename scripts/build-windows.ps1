param(
    [string]$BuildPython = (Join-Path $PSScriptRoot '../.superpowers/sdd/public-release/build-venv/Scripts/python.exe'),
    [string]$Iscc = (Join-Path $env:LOCALAPPDATA 'Temp/opencode/manhwa-release-tools/InnoSetup7/ISCC.exe'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../artifacts/windows-0.10.0')
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath($OutputDirectory)
$payload = Join-Path $output 'payload'
if (Test-Path -LiteralPath $payload) { throw 'Payload already exists; select a new empty output directory.' }
$info = & $BuildPython -c "import sys,sysconfig,json,importlib.metadata as m; print(json.dumps(dict(version='.'.join(map(str,sys.version_info[:3])),base=sys.base_prefix,site=sysconfig.get_path('purelib'),deps={x:m.version(x) for x in ('Pillow','pystray','six')})))"
if ($LASTEXITCODE -ne 0) { throw 'Build Python unavailable.' }
$info = $info | ConvertFrom-Json
if ($info.version -ne '3.13.15' -or $info.deps.Pillow -ne '13.0.0' -or $info.deps.pystray -ne '0.19.5' -or $info.deps.six -ne '1.17.0') { throw 'Pinned build interpreter/dependency version mismatch.' }
if (-not (Test-Path -LiteralPath $Iscc)) { throw 'Inno Setup compiler missing.' }
$compilerSignature = Get-AuthenticodeSignature -LiteralPath $Iscc
if ($compilerSignature.Status -ne 'Valid') { throw 'Inno Setup compiler signature invalid.' }
New-Item -ItemType Directory -Path $payload -Force | Out-Null
$cache = Join-Path $env:LOCALAPPDATA 'Temp/opencode/manhwa-release-tools'
New-Item -ItemType Directory -Path $cache -Force | Out-Null
$archive = Join-Path $cache 'python-3.13.15-embed-amd64.zip'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://www.python.org/ftp/python/3.13.15/python-3.13.15-embed-amd64.zip' -OutFile $archive -UseBasicParsing -TimeoutSec 120
}
$runtime = Join-Path $payload 'runtime'
Expand-Archive -LiteralPath $archive -DestinationPath $runtime
$signature = Get-AuthenticodeSignature -LiteralPath (Join-Path $runtime 'python.exe')
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Python Software Foundation') { throw 'Embedded Python publisher/signature invalid.' }
foreach ($file in @('_tkinter.pyd', 'tcl86t.dll', 'tk86t.dll', 'zlib1.dll')) {
    Copy-Item -LiteralPath (Join-Path $info.base "DLLs/$file") -Destination $runtime
}
# Explicit directories only: never copy base/user site-packages or local configs.
function Copy-RuntimeTree([string]$Source, [string]$Destination) {
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    foreach ($entry in Get-ChildItem -LiteralPath $Source) {
        if ($entry.Name -match '^(tests?|__pycache__|demos)$' -or $entry.Extension -in @('.pyc','.pyo','.lib','.pdb')) { continue }
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Runtime source link rejected.' }
        if ($entry.PSIsContainer) { Copy-RuntimeTree $entry.FullName (Join-Path $Destination $entry.Name) }
        else { Copy-Item -LiteralPath $entry.FullName -Destination $Destination }
    }
}
Copy-RuntimeTree (Join-Path $info.base 'Lib/tkinter') (Join-Path $runtime 'Lib/tkinter')
foreach ($name in @('tcl8','tcl8.6','tk8.6')) { Copy-RuntimeTree (Join-Path $info.base "tcl/$name") (Join-Path $runtime "tcl/$name") }
$site = Join-Path $runtime 'Lib/site-packages'
foreach ($name in @('PIL','pystray')) { Copy-RuntimeTree (Join-Path $info.site $name) (Join-Path $site $name) }
Copy-Item -LiteralPath (Join-Path $info.site 'six.py') -Destination $site
@('python313.zip','.', 'Lib', 'Lib/site-packages', '..', '../bridge', 'import site') | Set-Content -LiteralPath (Join-Path $runtime 'python313._pth') -Encoding Ascii
Copy-RuntimeTree (Join-Path $root 'companion/licenses') (Join-Path $payload 'licenses')
Copy-Item -LiteralPath (Join-Path $info.base 'tcl/tk8.6/license.terms') -Destination (Join-Path $payload 'licenses/Tk-license.terms')
$tclLicense = Join-Path $cache 'tcl-8.6.15-license.terms'
if (-not (Test-Path -LiteralPath $tclLicense)) {
    Invoke-WebRequest 'https://raw.githubusercontent.com/tcltk/tcl/core-8-6-15/license.terms' -OutFile $tclLicense -UseBasicParsing -TimeoutSec 30
}
Copy-Item -LiteralPath $tclLicense -Destination (Join-Path $payload 'licenses/Tcl-license.terms')
$zlibLicense = Join-Path $cache 'zlib-1.3.1-LICENSE'
if (-not (Test-Path -LiteralPath $zlibLicense)) {
    Invoke-WebRequest 'https://raw.githubusercontent.com/madler/zlib/v1.3.1/LICENSE' -OutFile $zlibLicense -UseBasicParsing -TimeoutSec 30
}
Copy-Item -LiteralPath $zlibLicense -Destination (Join-Path $payload 'licenses/zlib-LICENSE.txt')
& $BuildPython -B (Join-Path $PSScriptRoot 'release_manifest.py') $payload --source $root
if ($LASTEXITCODE -ne 0) { throw 'Product source privacy gate failed.' }
$csc = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
& $csc /nologo /target:winexe /optimize+ /reference:System.Windows.Forms.dll ('/out:' + (Join-Path $payload 'Manhwa Takip.exe')) (Join-Path $root 'companion/PortableLauncher.cs')
if ($LASTEXITCODE -ne 0) { throw 'Portable launcher compilation failed.' }
& $BuildPython -B (Join-Path $PSScriptRoot 'check_windows_payload.py') $payload
if ($LASTEXITCODE -ne 0) { throw 'Isolated runtime/launcher self-test failed.' }
& $BuildPython -B (Join-Path $PSScriptRoot 'release_manifest.py') $payload
if ($LASTEXITCODE -ne 0) { throw 'Payload privacy gate failed.' }
$metadata = @{ version='0.10.0'; python=$info.version; dependencies=$info.deps; embeddedZipSha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash; pythonPublisher=$signature.SignerCertificate.Subject; pythonSignature=[string]$signature.Status; compilerPublisher=$compilerSignature.SignerCertificate.Subject; windowsSigning='unsigned'; adb='not bundled; install official Android platform-tools separately' }
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'build-metadata.json') -Encoding UTF8
& $Iscc ('/DPayloadDir=' + $payload) ('/DOutputDir=' + $output) (Join-Path $root 'companion/setup.iss')
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed.' }
$hash = (Get-FileHash -LiteralPath (Join-Path $output 'Setup.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $output 'Setup.exe.sha256'), "$hash  Setup.exe`n")
Write-Output "WINDOWS_BUILD_OK $output"
