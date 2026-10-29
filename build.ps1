param(
    [string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug', 'assembleDebugAndroidTest')
)

$ErrorActionPreference = 'Stop'
$taskProject = $PSScriptRoot
$taskMapping = $null
$taskPushed = $false
$taskExit = 1
$taskSubst = Join-Path $env:SystemRoot 'System32\subst.exe'

# Query the Unicode target directly; subst's console encoding loses Turkish letters.
if (-not ('ManhwaBuild.DriveMap' -as [type])) {
    Add-Type -TypeDefinition @'
using System.Text;
using System.Runtime.InteropServices;
namespace ManhwaBuild {
    public static class DriveMap {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern uint QueryDosDevice(string name, StringBuilder target, int max);
    }
}
'@
}

try {
    $taskWorking = $taskProject
    # Gradle's Windows test-worker argument file loses non-ASCII classpath characters.
    # Map the same folder temporarily; do not copy or move the user's files.
    if ($taskProject -match '[^\x00-\x7F]') {
        $taskLetter = @('M', 'N', 'R', 'S', 'T', 'U') | Where-Object { -not (Test-Path "${_}:\") } | Select-Object -First 1
        if (-not $taskLetter) { throw 'Derleme için boş bir sürücü harfi bulunamadı.' }
        $taskMapping = "${taskLetter}:"
        & $taskSubst $taskMapping $taskProject
        if ($LASTEXITCODE -ne 0) { $taskMapping = $null; throw 'Geçici proje takma adı oluşturulamadı.' }
        $taskWorking = "$taskMapping\"
    }
    Push-Location -LiteralPath $taskWorking
    $taskPushed = $true
    & (Join-Path $taskWorking 'gradlew.bat') @Tasks --no-daemon --console=plain
    $taskExit = $LASTEXITCODE
} finally {
    if ($taskPushed) { Pop-Location }
    if ($taskMapping) {
        $taskTarget = [Text.StringBuilder]::new(4096)
        $taskFound = [ManhwaBuild.DriveMap]::QueryDosDevice($taskMapping, $taskTarget, $taskTarget.Capacity)
        if ($taskFound -gt 0 -and $taskTarget.ToString().Equals("\??\$taskProject", [StringComparison]::OrdinalIgnoreCase)) {
            & $taskSubst $taskMapping /d
            if ($LASTEXITCODE -ne 0) { Write-Warning 'Geçici sürücü eşlemesi kaldırılamadı.' }
        }
    }
}
exit $taskExit
