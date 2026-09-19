#!/usr/bin/env pwsh
# Create vaquarkhan/aiv-airflow-bench and inject synthetic PRs for live AIV CI.
$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$Bench = Join-Path $Root "benchmarks\airflow"
$RepoName = "aiv-airflow-bench"
$Full = "vaquarkhan/$RepoName"
$Work = Join-Path $env:TEMP "aiv-airflow-bench-seed"

if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
New-Item -ItemType Directory -Path $Work | Out-Null

gh repo view $Full 1>$null 2>$null
if ($LASTEXITCODE -ne 0) {
  gh repo create $Full --public --description "AIV Integrity Gate Airflow corpus bench (injected PRs for CI)" --clone=false
  if ($LASTEXITCODE -ne 0) { throw "Failed to create $Full" }
}
$ErrorActionPreference = "Stop"

Push-Location $Work
git init -q
git checkout -b main

$readme = @'
# aiv-airflow-bench

Test repository for AIV Integrity Gate Airflow benchmarks.

Injected synthetic AI-slop fixtures and clean controls as pull requests.
Workflow builds aiv-cli from vaquarkhan/aiv-integrity-gate and runs with --label-pr-on-advisory.

Provenance for real apache/airflow PRs lives in the gate repo under benchmarks/airflow/reports/.
This is not apache/airflow itself.
'@
Set-Content -Path README.md -Value $readme -Encoding utf8

Copy-Item -Recurse (Join-Path $Bench ".aiv") (Join-Path $Work ".aiv")
New-Item -ItemType Directory -Force -Path ".github\workflows" | Out-Null
Copy-Item (Join-Path $Bench "workflows\aiv.yml") ".github\workflows\aiv.yml"

# Point workflow at main of aiv-integrity-gate (already does)
git config user.email "bench@aiv.local"
git config user.name "AIV Bench"
git add -A
git commit -q -m "seed: AIV config and workflow"
git remote add origin "https://github.com/$Full.git"
git push -u origin main --force

$fixtures = @(
  @{ branch = "case/ai-edit-artifact"; file = "fixtures\ai-slop\edit_artifact_operator.py"; dest = "providers\bench\edit_artifact_operator.py"; title = "bench: synthetic AI edit-artifact (expect AIV FAIL)" },
  @{ branch = "case/ai-attribution"; file = "fixtures\ai-slop\chatgpt_attribution.py"; dest = "providers\bench\chatgpt_attribution.py"; title = "bench: synthetic ChatGPT attribution (expect AIV FAIL)" },
  @{ branch = "case/merge-conflict"; file = "fixtures\ai-slop\conflict_markers.py"; dest = "providers\bench\conflict_markers.py"; title = "bench: synthetic merge conflict markers (expect AIV FAIL)" },
  @{ branch = "case/clean-control"; file = "fixtures\real\clean_sftp_exception.py"; dest = "providers\bench\clean_sftp_exception.py"; title = "bench: clean control (expect AIV PASS)" }
)

foreach ($f in $fixtures) {
  git checkout main -q
  git checkout -B $f.branch
  $dest = Join-Path $Work $f.dest
  New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
  Copy-Item (Join-Path $Bench $f.file) $dest -Force
  git add -A
  git commit -q -m $f.title
  git push -u origin $f.branch --force
  $pr = gh pr list --repo $Full --head $f.branch --json number --jq ".[0].number"
  if (-not $pr) {
    gh pr create --repo $Full --base main --head $f.branch --title $f.title --body "Injected from aiv-integrity-gate for live AIV CI validation."
  }
}

Pop-Location
Write-Host "Done: https://github.com/$Full"
gh pr list --repo $Full
