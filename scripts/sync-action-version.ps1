# Sets action.yml inputs.aiv-version default to the reactor version from pom.xml.
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $Root
try {
    $ver = mvn -q -DforceStdout help:evaluate -Dexpression=project.version -f pom.xml
} finally {
    Pop-Location
}
if (-not $ver) { throw 'Could not read project.version from Maven' }
$path = Join-Path $Root 'action.yml'
$text = [System.IO.File]::ReadAllText($path)
$pattern = "(?ms)(^\s+aiv-version:\s*\r?\n\s+description:.*?\r?\n\s+required:.*?\r?\n\s+default: ')([0-9.]+)(')"
$newText = [regex]::Replace($text, $pattern, "`${1}$ver`${3}")
if ($newText -eq $text) { throw 'Could not find aiv-version default block in action.yml' }
[System.IO.File]::WriteAllText($path, $newText)
Write-Host "action.yml: inputs.aiv-version default set to $ver"
