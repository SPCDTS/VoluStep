[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$bundletoolVersion = '1.18.3'
$expectedSha256 = 'A099CFA1543F55593BC2ED16A70A7C67FE54B1747BB7301F37FDFD6D91028E29'
$downloadUri = "https://github.com/google/bundletool/releases/download/$bundletoolVersion/bundletool-all-$bundletoolVersion.jar"
$destinationDirectory = Join-Path $projectRoot '.toolchains\bundletool'
$destinationPath = Join-Path $destinationDirectory "bundletool-all-$bundletoolVersion.jar"

function Test-ExpectedBundletool {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    $actualSha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualSha256 -cne $expectedSha256) {
        throw "现有 bundletool 校验失败，不会覆盖：$Path。期望 SHA-256：$expectedSha256；实际：$actualSha256"
    }
    return $true
}

if (Test-ExpectedBundletool -Path $destinationPath) {
    Write-Host "bundletool $bundletoolVersion 已安装且校验通过：$destinationPath"
    Write-Output $destinationPath
    return
}

New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
$temporaryPath = Join-Path $destinationDirectory (
    ".bundletool-$bundletoolVersion-$PID-$([Guid]::NewGuid().ToString('N')).tmp"
)

try {
    Invoke-WebRequest -UseBasicParsing -Uri $downloadUri -OutFile $temporaryPath
    $downloadedSha256 = (Get-FileHash -LiteralPath $temporaryPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($downloadedSha256 -cne $expectedSha256) {
        throw "下载的 bundletool 校验失败。期望 SHA-256：$expectedSha256；实际：$downloadedSha256"
    }

    try {
        [IO.File]::Move($temporaryPath, $destinationPath)
    } catch [IO.IOException] {
        # 并发安装只接受另一个进程已经原子安装了完全相同的文件。
        if (-not (Test-ExpectedBundletool -Path $destinationPath)) {
            throw
        }
    }

    Write-Host "已安装 bundletool $bundletoolVersion：$destinationPath"
    Write-Host "SHA-256：$expectedSha256"
    Write-Output $destinationPath
} finally {
    if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryPath -Force
    }
}
