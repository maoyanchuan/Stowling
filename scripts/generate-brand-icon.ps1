[CmdletBinding()]
param(
    [string]$BrowserPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-RequiredFile {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Read-BigEndianUInt32 {
    [CmdletBinding()]
    [OutputType([uint32])]
    param(
        [Parameter(Mandatory)]
        [System.IO.BinaryReader]$Reader
    )

    $bytes = $Reader.ReadBytes(4)
    return [uint32](
        ([uint32]$bytes[0] -shl 24) -bor
        ([uint32]$bytes[1] -shl 16) -bor
        ([uint32]$bytes[2] -shl 8) -bor
        [uint32]$bytes[3]
    )
}

function Resolve-BrowserPath {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [string]$RequestedPath
    )

    $candidates = @(
        $RequestedPath,
        $env:STOWLING_BROWSER,
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
    )
    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and
                (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "Chrome or Edge is required. Set STOWLING_BROWSER or pass -BrowserPath."
}

function Assert-PngMetadata {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $reader = [System.IO.BinaryReader]::new($stream)
        $signature = $reader.ReadBytes(8)
        $expectedSignature = [byte[]](137, 80, 78, 71, 13, 10, 26, 10)
        if (-not [System.Linq.Enumerable]::SequenceEqual($signature, $expectedSignature)) {
            throw "Browser did not produce a valid PNG: $Path"
        }
        $null = Read-BigEndianUInt32 -Reader $reader
        $chunkType = $reader.ReadBytes(4)
        if ([System.Text.Encoding]::ASCII.GetString($chunkType) -ne "IHDR") {
            throw "Browser PNG is missing its IHDR chunk."
        }
        $width = Read-BigEndianUInt32 -Reader $reader
        $height = Read-BigEndianUInt32 -Reader $reader
        $null = $reader.ReadByte()
        $colorType = $reader.ReadByte()
        if ($width -ne 512 -or $height -ne 512) {
            throw "Expected a 512x512 PNG, got ${width}x${height}."
        }
        if ($colorType -ne 6) {
            throw "Expected RGBA PNG output for transparency, got color type $colorType."
        }
    } finally {
        $stream.Dispose()
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$svgPath = Join-Path $repoRoot "src/main/resources/assets/stowling/icon.svg"
$pngPath = Join-Path $repoRoot "src/main/resources/assets/stowling/icon.png"

$svg = Read-RequiredFile -Path $svgPath
$resolvedBrowser = Resolve-BrowserPath -RequestedPath $BrowserPath
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
    "stowling-brand-icon-" + [guid]::NewGuid().ToString("N")
)
$temporaryHtml = Join-Path $temporaryRoot "icon.html"
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    $svgBase64 = [Convert]::ToBase64String(
        [System.Text.Encoding]::UTF8.GetBytes($svg)
    )
    $html = @"
<!doctype html>
<html>
<head>
    <meta charset="utf-8">
    <style>
        html, body {
            width: 512px;
            height: 512px;
            margin: 0;
            overflow: hidden;
            background: transparent;
        }
        img {
            display: block;
            width: 512px;
            height: 512px;
        }
    </style>
</head>
<body>
    <img src="data:image/svg+xml;base64,$svgBase64" alt="">
</body>
</html>
"@
    Set-Content -LiteralPath $temporaryHtml -Value $html -Encoding utf8
    $browserArguments = @(
        "--headless",
        "--disable-gpu",
        "--disable-extensions",
        "--hide-scrollbars",
        "--no-default-browser-check",
        "--no-first-run",
        "--force-device-scale-factor=1",
        "--window-size=512,512",
        "--default-background-color=00000000",
        "--virtual-time-budget=1000",
        "--screenshot=$pngPath",
        ([uri]$temporaryHtml).AbsoluteUri
    )
    $process = Start-Process -FilePath $resolvedBrowser `
        -ArgumentList $browserArguments `
        -WindowStyle Hidden `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Browser icon export failed with exit code $($process.ExitCode)."
    }
    Assert-PngMetadata -Path $pngPath
    Write-Host "Generated $pngPath from icon.svg using $resolvedBrowser"
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
