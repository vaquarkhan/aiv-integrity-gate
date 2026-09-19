# Runs AIV against true-positive fixtures packed as a two-commit git repo (expect FAIL).
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$JarVersion = "1.0.4"
Set-Location $Root
Write-Host "Building aiv-cli..."
mvn -q -pl aiv-cli -am package -DskipTests
$Jar = Join-Path $Root "aiv-cli\target\aiv-cli-$JarVersion.jar"
$Fix = Join-Path $Root "benchmarks\true-positive\fixtures"
$Tmp = Join-Path $env:TEMP ("aiv-hb-" + [guid]::NewGuid().ToString("n"))
New-Item -ItemType Directory -Path $Tmp | Out-Null
try {
  Push-Location $Tmp
  git init -q
  git config user.email "aiv-demo@example.com"
  git config user.name "AIV Demo"
  Set-Content -Path "README.md" -Value "base`n"
  git add README.md
  git commit -q -m "base"
  Copy-Item -Recurse -Force (Join-Path $Fix "*") $Tmp
  git add -A
  git commit -q -m "agent dump breakage"
  & java -jar $Jar --quiet --workspace . --diff HEAD~1
  $code = $LASTEXITCODE
  Pop-Location
  if ($code -eq 0) {
    throw "Expected AIV to FAIL on high-breakage fixtures, but exit was 0"
  }
  Write-Host "Demo OK: AIV blocked the breakage (exit $code)."
} finally {
  if (Test-Path $Tmp) { Remove-Item -Recurse -Force $Tmp }
}
