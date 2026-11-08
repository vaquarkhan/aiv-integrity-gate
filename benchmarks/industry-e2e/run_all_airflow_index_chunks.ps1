# Continues Airflow index scoring after an optional wait PID exits.
# Usage:
#   .\run_all_airflow_index_chunks.ps1 -WaitPid 11704 -StartChunk 1
#   .\run_all_airflow_index_chunks.ps1 -StartChunk 0
param(
  [int]$WaitPid = 0,
  [int]$StartChunk = 0,
  [int]$EndChunk = 7,
  [int]$ChunkSize = 2000
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$logDir = Join-Path $PSScriptRoot "results-airflow-index"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$supervisorLog = Join-Path $logDir "supervisor.log"

function Write-Log([string]$msg) {
  $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-ddTHH:mm:ss"), $msg
  Add-Content -Path $supervisorLog -Value $line
  Write-Output $line
}

if ($WaitPid -gt 0) {
  Write-Log "waiting for pid $WaitPid to exit before starting chunk $StartChunk"
  try {
    Wait-Process -Id $WaitPid -ErrorAction SilentlyContinue
  } catch {
    Write-Log "wait-process ended: $_"
  }
  Write-Log "pid $WaitPid finished (or already gone)"
}

for ($chunk = $StartChunk; $chunk -le $EndChunk; $chunk++) {
  $chunkLog = Join-Path $logDir ("chunk-{0}.log" -f $chunk)
  Write-Log "starting chunk $chunk (size $ChunkSize) -> $chunkLog"
  # Start-Process cannot redirect stdout and stderr to the same path on Windows.
  & python -u run_airflow_index_chunks.py --chunk $chunk --chunk-size $ChunkSize 2>&1 |
    Tee-Object -FilePath $chunkLog
  $code = $LASTEXITCODE
  Write-Log ("chunk {0} exit={1}" -f $chunk, $code)
  if ($code -eq 3) {
    Write-Log "chunk $chunk stopped on GitHub rate limit / fetcher stop; supervisor pauses here"
    break
  }
  if ($code -ne 0) {
    Write-Log "chunk $chunk failed; supervisor stops"
    break
  }
}

Write-Log "supervisor done"
