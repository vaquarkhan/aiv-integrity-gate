#!/usr/bin/env pwsh
# Score Airflow benchmark fixtures with a local aiv-cli JAR.
# Run from aiv-integrity-gate repo root:
#   .\benchmarks\airflow\scripts\run-benchmark.ps1

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$Bench = Join-Path $Root "benchmarks\airflow"
$ResultsDir = Join-Path $Bench "results"
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

$Version = (& mvn -q -DforceStdout help:evaluate "-Dexpression=project.version" -f (Join-Path $Root "pom.xml")).Trim()
$Jar = Join-Path $Root "aiv-cli\target\aiv-cli-$Version.jar"
if (-not (Test-Path $Jar)) {
    Write-Host "Building aiv-cli..."
    Push-Location $Root
    mvn -B -ntp -pl aiv-cli -am package "-DskipTests"
    Pop-Location
}
if (-not (Test-Path $Jar)) {
    throw "Missing $Jar"
}

$Work = Join-Path $env:TEMP ("aiv-airflow-bench-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $Work | Out-Null
try {
    Push-Location $Work
    git init -q
    git config user.email "bench@aiv.local"
    git config user.name "AIV Bench"
    Copy-Item -Recurse (Join-Path $Bench ".aiv") (Join-Path $Work ".aiv")
    New-Item -ItemType Directory -Force -Path (Join-Path $Work "providers") | Out-Null
    Set-Content -Path (Join-Path $Work "providers\__init__.py") -Value ""
    git add -A
    git commit -q -m "base"
    $Base = (git rev-parse HEAD).Trim()

    $Cases = Get-Content (Join-Path $Bench "corpus\cases.json") -Raw | ConvertFrom-Json
    $Rows = @()
    foreach ($case in $Cases.cases) {
        if ($case.source -ne "fixture") { continue }
        $src = Join-Path $Bench $case.path
        if (-not (Test-Path $src)) {
            Write-Warning "Missing fixture $($case.path)"
            continue
        }
        git checkout -q $Base
        git clean -fdq
        $destRel = $case.path -replace "^fixtures/", "providers/bench/"
        $dest = Join-Path $Work $destRel
        New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
        Copy-Item $src $dest -Force
        git add -A
        git commit -q -m "case $($case.id)"
        $jsonOut = Join-Path $ResultsDir ("case-$($case.id).json")
        $code = 0
        & java -jar $Jar --workspace $Work --diff $Base --head HEAD --quiet --output-json $jsonOut
        $code = $LASTEXITCODE
        $Rows += [pscustomobject]@{
            id = $case.id
            expected = $case.expected
            exit_code = $code
            report = "results/case-$($case.id).json"
        }
        Write-Host ("{0}: exit={1} expected={2}" -f $case.id, $code, $case.expected)
    }

    $Summary = [pscustomobject]@{
        generated_at = (Get-Date).ToString("o")
        aiv_version = $Version
        jar = $Jar
        cases = $Rows
    }
    $SummaryPath = Join-Path $ResultsDir "summary.json"
    $Summary | ConvertTo-Json -Depth 6 | Set-Content -Path $SummaryPath -Encoding utf8
    Write-Host "Wrote $SummaryPath"
}
finally {
    Pop-Location -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
