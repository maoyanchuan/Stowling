[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$LogDirectory = ".\logs\headless"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedLogDirectory = [System.IO.Path]::GetFullPath($LogDirectory)
$pidFile = Join-Path $resolvedLogDirectory "stowling-headless.pid"
if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) {
    Write-Output "running=false"
    return
}

try {
    $metadata = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
    $processId = [int]$metadata.processId
    $startedAtUtc = [datetime]$metadata.startedAtUtc
}
catch {
    throw "The HeadlessMC PID file is invalid."
}

$process = Get-Process -Id $processId -ErrorAction SilentlyContinue
$matchesStartTime = $null -ne $process -and
    $process.StartTime.ToUniversalTime() -eq $startedAtUtc.ToUniversalTime()
if (-not $matchesStartTime) {
    Remove-Item -LiteralPath $pidFile -Force
    Write-Output "running=false"
    return
}

if ($PSCmdlet.ShouldProcess("HeadlessMC process $processId", "Stop")) {
    Stop-Process -Id $processId
    Wait-Process -Id $processId -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $pidFile -Force
    Write-Output "stopped=true"
}
