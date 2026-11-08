# Sets action.yml inputs.aiv-version default to the reactor version from pom.xml.
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $Root
try {
    # Quote -D args so PowerShell does not split on '=' / '.'
    $ver = (& mvn -q "-DforceStdout" "help:evaluate" "-Dexpression=project.version" "-f" "pom.xml" | Select-Object -Last 1).Trim()
} finally {
    Pop-Location
}
if (-not $ver -or $ver -match '[<>\s]' -or $ver -notmatch '^[0-9]') {
    throw "Could not read a clean project.version from Maven (got: '$ver')"
}
$path = Join-Path $Root 'action.yml'
$text = [System.IO.File]::ReadAllText($path)
$pattern = "(?ms)(^\s+aiv-version:\s*\r?\n\s+description:.*?\r?\n\s+required:.*?\r?\n\s+default: ')([^']*)(')"
$newText = [regex]::Replace($text, $pattern, "`${1}$ver`${3}")
if ($newText -eq $text) { throw 'Could not find aiv-version default block in action.yml' }
[System.IO.File]::WriteAllText($path, $newText)
Write-Host "action.yml: inputs.aiv-version default set to $ver"
