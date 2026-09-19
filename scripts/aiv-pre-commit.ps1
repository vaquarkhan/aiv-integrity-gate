# AIV pre-commit / local shift-left runner (Windows PowerShell).
# Default: gate only staged changes (index tree vs HEAD).
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Resolve-Path (Join-Path $ScriptDir "..")
if ($env:AIV_WORKSPACE) {
    Set-Location $env:AIV_WORKSPACE
} else {
    Set-Location (Get-Location)
}

$Version = if ($env:AIV_VERSION) { $env:AIV_VERSION } else { "1.0.4" }
$Jar = $env:AIV_CLI_JAR
if (-not $Jar) {
    if (Test-Path ".\aiv-cli.jar") {
        $Jar = (Resolve-Path ".\aiv-cli.jar").Path
    } elseif (Test-Path (Join-Path $Root "aiv-cli\target\aiv-cli-$Version.jar")) {
        $Jar = (Join-Path $Root "aiv-cli\target\aiv-cli-$Version.jar")
    } else {
        $Cache = Join-Path $env:LOCALAPPDATA "aiv"
        New-Item -ItemType Directory -Force -Path $Cache | Out-Null
        $Jar = Join-Path $Cache "aiv-cli-$Version.jar"
        if (-not (Test-Path $Jar)) {
            $Url = "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/$Version/aiv-cli-$Version.jar"
            Write-Host "AIV: downloading CLI $Version from Maven Central..."
            Invoke-WebRequest -Uri $Url -OutFile $Jar -UseBasicParsing
        }
    }
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "AIV: java not found (need JDK 17+)"
}

$Base = $env:AIV_DIFF_BASE
$Head = $env:AIV_DIFF_HEAD
if (-not $Base -or -not $Head) {
    git rev-parse --is-inside-work-tree 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "AIV: not a git repo; set AIV_DIFF_BASE and AIV_DIFF_HEAD"
    }
    git diff --cached --quiet
    if ($LASTEXITCODE -eq 0) {
        Write-Host "AIV: no staged changes; skipping."
        exit 0
    }
    $Base = "HEAD"
    $Tree = (git write-tree).Trim()
    $Head = (git commit-tree $Tree -p HEAD -m "aiv-pre-commit-index").Trim()
}

& java -jar $Jar --quiet --workspace . --diff $Base --head $Head
exit $LASTEXITCODE
