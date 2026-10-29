param(
    [Parameter(Mandatory = $true)]
    [string]$Destination
)
$ErrorActionPreference = 'Stop'
# The exporter chooses explicit source paths; .gitignore is not its security boundary.
# No build, installation, account operation, Git command, or recursive copy is run here.
& python (Join-Path $PSScriptRoot 'public_export.py') --destination $Destination
if ($LASTEXITCODE -ne 0) {
    throw 'Public export refused or incomplete. Review path/category findings; do not publish.'
}
