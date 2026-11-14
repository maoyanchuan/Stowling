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

function Assert-Contains {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Content,

        [Parameter(Mandatory)]
        [string]$Expected,

        [Parameter(Mandatory)]
        [string]$Context
    )

    if (-not $Content.Contains($Expected)) {
        throw "$Context must contain: $Expected"
    }
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
$stylesPath = Join-Path $repoRoot "src/main/resources/stowling-web/styles.css"
$svgPath = Join-Path $repoRoot "src/main/resources/assets/stowling/icon.svg"
$pngPath = Join-Path $repoRoot "src/main/resources/assets/stowling/icon.png"

$styles = Read-RequiredFile -Path $stylesPath
$brandMatch = [regex]::Match(
    $styles,
    "(?ms)^\.brand-mark\s*\{(?<body>.*?)^\}"
)
if (-not $brandMatch.Success) {
    throw "Could not find the .brand-mark block in styles.css."
}
$brandStyles = $brandMatch.Groups["body"].Value
foreach ($declaration in @(
        "width: 46px;",
        "height: 46px;",
        "border-radius: 14px 14px 14px 4px;",
        "color: #ffffff;",
        "font-size: 1.42rem;",
        "font-weight: 800;",
        "background: linear-gradient(145deg, #3f8c64, #1c5138);"
    )) {
    Assert-Contains -Content $brandStyles -Expected $declaration -Context ".brand-mark"
}

$svg = Read-RequiredFile -Path $svgPath
foreach ($fragment in @(
        'viewBox="0 0 46 46"',
        'x1="0.1495" y1="0" x2="0.8505" y2="1"',
        'stop-color="#3f8c64"',
        'stop-color="#1c5138"',
        'fill="#ffffff"',
        'font-size="22.72"',
        'font-weight="800"',
        '>S</text>'
    )) {
    Assert-Contains -Content $svg -Expected $fragment -Context "icon.svg"
}
if ($svg -match "<(filter|rect)\b" -or $svg -match "\bstroke=") {
    throw "icon.svg must not add shadows, borders, or a background rectangle."
}

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
