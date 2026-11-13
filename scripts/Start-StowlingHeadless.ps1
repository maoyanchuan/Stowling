[CmdletBinding()]
param(
    [string]$HeadlessMcPath = ".\HeadlessMC.exe",
    [string]$WorkingDirectory = ".",
    [string]$LogDirectory = ".\logs\headless",
    [string[]]$HeadlessMcArguments = @(),
    [int]$InitialBackoffSeconds = 2,
    [int]$MaxBackoffSeconds = 60,
    [int]$StableRunSeconds = 300,
    [int]$MaxLogFiles = 20,
    [switch]$UseLocalProxy,
    [switch]$DryRun,
    [switch]$Status
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-NormalizedPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return [System.IO.Path]::GetFullPath($Path)
}

function Get-PidFilePath {
    param([Parameter(Mandatory = $true)][string]$ResolvedLogDirectory)

    return Join-Path $ResolvedLogDirectory "stowling-headless.pid"
}

function Get-HeadlessStatus {
    param([Parameter(Mandatory = $true)][string]$PidFile)

    if (-not (Test-Path -LiteralPath $PidFile -PathType Leaf)) {
        return [pscustomobject]@{ Running = $false; ProcessId = $null }
    }

    try {
        $metadata = Get-Content -LiteralPath $PidFile -Raw | ConvertFrom-Json
        $processId = [int]$metadata.processId
        $startedAtUtc = [datetime]$metadata.startedAtUtc
    }
    catch {
        return [pscustomobject]@{ Running = $false; ProcessId = $null }
    }

    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    $matchesStartTime = $null -ne $process -and
        $process.StartTime.ToUniversalTime() -eq $startedAtUtc.ToUniversalTime()
    return [pscustomobject]@{
        Running = $matchesStartTime
        ProcessId = if ($matchesStartTime) { $processId } else { $null }
    }
}

function Remove-OldLogs {
    param(
        [Parameter(Mandatory = $true)][string]$ResolvedLogDirectory,
        [Parameter(Mandatory = $true)][int]$Keep
    )

    $oldLogs = Get-ChildItem -LiteralPath $ResolvedLogDirectory -File |
        Where-Object { $_.Name -like "headless-*.log" } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -Skip $Keep
    foreach ($log in $oldLogs) {
        Remove-Item -LiteralPath $log.FullName -Force
    }
}

if ($InitialBackoffSeconds -lt 1 -or $MaxBackoffSeconds -lt $InitialBackoffSeconds) {
    throw "Backoff values are invalid."
}
if ($StableRunSeconds -lt 1 -or $MaxLogFiles -lt 2) {
    throw "StableRunSeconds and MaxLogFiles must be positive."
}

$resolvedWorkingDirectory = Get-NormalizedPath $WorkingDirectory
$resolvedLogDirectory = Get-NormalizedPath $LogDirectory
$pidFile = Get-PidFilePath $resolvedLogDirectory

if ($Status) {
    $headlessStatus = Get-HeadlessStatus $pidFile
    Write-Output ("running={0}" -f $headlessStatus.Running.ToString().ToLowerInvariant())
    if ($headlessStatus.Running) {
        Write-Output ("processId={0}" -f $headlessStatus.ProcessId)
    }
    return
}

if ($DryRun) {
    Write-Output "dryRun=true"
    Write-Output ("executable={0}" -f (Get-NormalizedPath $HeadlessMcPath))
    Write-Output ("workingDirectory={0}" -f $resolvedWorkingDirectory)
    Write-Output ("logDirectory={0}" -f $resolvedLogDirectory)
    Write-Output ("argumentCount={0}" -f $HeadlessMcArguments.Count)
    Write-Output ("localProxy={0}" -f $UseLocalProxy.ToString().ToLowerInvariant())
    return
}

if (-not (Test-Path -LiteralPath $HeadlessMcPath -PathType Leaf)) {
    throw "HeadlessMC executable was not found."
}
if (-not (Test-Path -LiteralPath $resolvedWorkingDirectory -PathType Container)) {
    throw "Working directory was not found."
}

New-Item -ItemType Directory -Path $resolvedLogDirectory -Force | Out-Null
$existingStatus = Get-HeadlessStatus $pidFile
if ($existingStatus.Running) {
    throw "HeadlessMC is already running."
}

$backoffSeconds = $InitialBackoffSeconds
while ($true) {
    Remove-OldLogs $resolvedLogDirectory $MaxLogFiles
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
    $standardOutput = Join-Path $resolvedLogDirectory "headless-$timestamp.out.log"
    $standardError = Join-Path $resolvedLogDirectory "headless-$timestamp.err.log"
    $startedAt = Get-Date

    if ($UseLocalProxy) {
        $env:HTTP_PROXY = "http://127.0.0.1:10808"
        $env:HTTPS_PROXY = "http://127.0.0.1:10808"
        $env:ALL_PROXY = "socks5://127.0.0.1:10808"
        $env:NO_PROXY = "127.0.0.1,localhost"
    }

    $process = Start-Process `
        -FilePath $HeadlessMcPath `
        -ArgumentList $HeadlessMcArguments `
        -WorkingDirectory $resolvedWorkingDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $standardOutput `
        -RedirectStandardError $standardError `
        -PassThru
    $processMetadata = [pscustomobject]@{
        processId = $process.Id
        startedAtUtc = $process.StartTime.ToUniversalTime().ToString("O")
    }
    $processMetadata | ConvertTo-Json -Compress |
        Set-Content -LiteralPath $pidFile -Encoding Ascii
    Wait-Process -Id $process.Id
    $process.Refresh()
    $exitCode = $process.ExitCode

    if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
        $recordedMetadata = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
        if ([int]$recordedMetadata.processId -eq $process.Id) {
            Remove-Item -LiteralPath $pidFile -Force
        }
    }

    Remove-OldLogs $resolvedLogDirectory $MaxLogFiles

    if ($exitCode -eq 0) {
        return
    }

    $runSeconds = ((Get-Date) - $startedAt).TotalSeconds
    if ($runSeconds -ge $StableRunSeconds) {
        $backoffSeconds = $InitialBackoffSeconds
    }
    Start-Sleep -Seconds $backoffSeconds
    $backoffSeconds = [Math]::Min($backoffSeconds * 2, $MaxBackoffSeconds)
}
