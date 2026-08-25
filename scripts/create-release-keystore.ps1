[CmdletBinding()]
param(
    [ValidateSet('All', 'AppSigning', 'PlayUpload')]
    [string]$Purpose = 'All',
    [ValidateRange(9125, 36500)]
    [int]$ValidityDays = 10000,
    [ValidateNotNullOrEmpty()]
    [string]$DistinguishedName = 'CN=VoluStep'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'android-env.ps1')

$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) {
    throw "未找到 keytool：$keytool"
}

$keystoreDirectory = Join-Path $script:ProjectRoot 'keystore'
$allTargets = @(
    [PSCustomObject]@{
        Purpose = 'AppSigning'
        Label = '跨商店应用签名密钥'
        FileName = 'volustep-app-signing.p12'
        Alias = 'volustep-app-signing'
    },
    [PSCustomObject]@{
        Purpose = 'PlayUpload'
        Label = 'Google Play 独立上传密钥'
        FileName = 'volustep-play-upload.p12'
        Alias = 'volustep-play-upload'
    }
)
$targets = if ($Purpose -eq 'All') {
    $allTargets
} else {
    @($allTargets | Where-Object Purpose -eq $Purpose)
}

# 先检查全部目标，避免 All 模式创建一半后才发现另一个文件会被覆盖。
foreach ($target in $targets) {
    $targetPath = Join-Path $keystoreDirectory $target.FileName
    if (Test-Path -LiteralPath $targetPath) {
        throw "签名库已存在，不会覆盖：$targetPath"
    }
}

New-Item -ItemType Directory -Force -Path $keystoreDirectory | Out-Null

function Read-ConfirmedPassword {
    param([Parameter(Mandatory)][string]$Label)

    $firstSecure = Read-Host "请输入$($Label)密码（至少 12 位）" -AsSecureString
    $secondSecure = Read-Host "请再次输入$($Label)密码" -AsSecureString
    $firstCredential = [PSCredential]::new('unused', $firstSecure)
    $secondCredential = [PSCredential]::new('unused', $secondSecure)
    $firstPlain = $firstCredential.GetNetworkCredential().Password
    $secondPlain = $secondCredential.GetNetworkCredential().Password
    try {
        if ($firstPlain.Length -lt 12) {
            throw '密码至少需要 12 位。'
        }
        if ($firstPlain -cne $secondPlain) {
            throw '两次输入的密码不一致。'
        }
        return $firstPlain
    } finally {
        $firstCredential = $null
        $secondCredential = $null
        $firstSecure = $null
        $secondSecure = $null
        $firstPlain = $null
        $secondPlain = $null
    }
}

function New-ReleaseKeystore {
    param([Parameter(Mandatory)]$Target)

    $keystorePath = Join-Path $keystoreDirectory $Target.FileName
    $temporaryKeystorePath = Join-Path $keystoreDirectory (
        ".$($Target.FileName).$PID-$([Guid]::NewGuid().ToString('N')).tmp"
    )
    $password = Read-ConfirmedPassword -Label "$($Target.Label)的签名库与私钥"
    $secretVariableName = 'VOLUSTEP_KEYTOOL_PASSWORD_' +
        ([Guid]::NewGuid().ToString('N').ToUpperInvariant())
    try {
        [Environment]::SetEnvironmentVariable($secretVariableName, $password, 'Process')
        $arguments = @(
            '-J-Duser.language=en',
            '-J-Duser.country=US',
            '-genkeypair',
            '-noprompt',
            '-keystore', $temporaryKeystorePath,
            '-storetype', 'PKCS12',
            '-storepass:env', $secretVariableName,
            '-keypass:env', $secretVariableName,
            '-alias', $Target.Alias,
            '-keyalg', 'RSA',
            '-keysize', '4096',
            '-sigalg', 'SHA256withRSA',
            '-validity', $ValidityDays,
            '-dname', $DistinguishedName
        )
        & $keytool @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "keytool 创建$($Target.Label)失败。"
        }

        $certificateOutput = & $keytool `
            '-J-Duser.language=en' `
            '-J-Duser.country=US' `
            -list -v `
            -keystore $temporaryKeystorePath `
            -storetype PKCS12 `
            -storepass:env $secretVariableName `
            -alias $Target.Alias 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "无法读取刚创建的$($Target.Label)证书。"
        }
        $certificateText = ($certificateOutput | ForEach-Object { $_.ToString() }) -join "`n"
        $fingerprintMatch = [regex]::Match(
            $certificateText,
            'SHA256:\s*(?<Fingerprint>[0-9A-Fa-f:]{64,95})'
        )
        if (-not $fingerprintMatch.Success) {
            throw "无法解析$($Target.Label)的 SHA-256 证书指纹。"
        }

        try {
            # File.Move 在目标已存在时失败；同目录移动不会覆盖，且在本卷内原子完成。
            [IO.File]::Move($temporaryKeystorePath, $keystorePath)
        } catch [IO.IOException] {
            if (Test-Path -LiteralPath $keystorePath) {
                throw "签名库已被另一个进程创建，不会覆盖：$keystorePath"
            }
            throw
        }

        Write-Host "已创建$($Target.Label)：$keystorePath"
        Write-Host "密钥别名：$($Target.Alias)"
        Write-Host "证书 SHA-256：$($fingerprintMatch.Groups['Fingerprint'].Value.ToUpperInvariant())"
    } finally {
        [Environment]::SetEnvironmentVariable($secretVariableName, $null, 'Process')
        $password = $null
        # 临时名由本进程随机生成；永不删除最终目标或其他进程的文件。
        if ([IO.File]::Exists($temporaryKeystorePath)) {
            [IO.File]::Delete($temporaryKeystorePath)
        }
    }
}

foreach ($target in $targets) {
    New-ReleaseKeystore -Target $target
}

Write-Host ''
Write-Host '应用签名密钥用于各非 Play 商店，并在 Play App Signing 中选择“提供自己的密钥”时使用。'
Write-Host 'Play 上传密钥只用于签署上传到 Google Play 的 AAB，不能用于其他商店的正式 APK。'
Write-Host '脚本没有保存密码。请立即对签名库做至少两份加密离线备份，并单独保存证书指纹。'
