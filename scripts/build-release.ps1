[CmdletBinding()]
param(
    [ValidateSet('AppSigning', 'PlayUpload')]
    [string]$SigningProfile = 'AppSigning',
    [string]$KeystorePath,
    [string]$KeyAlias,
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ExpectedCertificateSha256,
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$PrivacyPolicyUrl
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'android-env.ps1')

function Normalize-Sha256Fingerprint {
    param([Parameter(Mandatory)][string]$Value)

    $normalized = ($Value -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    if ($normalized.Length -ne 64) {
        throw "证书 SHA-256 指纹格式无效：$Value"
    }
    return $normalized
}

function Format-Sha256Fingerprint {
    param([Parameter(Mandatory)][string]$Value)

    $normalized = Normalize-Sha256Fingerprint -Value $Value
    return (($normalized -split '(.{2})' | Where-Object Length -eq 2) -join ':')
}

function Resolve-HttpsPrivacyPolicyUrl {
    param([Parameter(Mandatory)][string]$Value)

    $candidate = $Value.Trim()
    $uri = $null
    $valid = [Uri]::TryCreate($candidate, [UriKind]::Absolute, [ref]$uri)
    if (-not $valid -or
        -not [string]::Equals(
            $uri.Scheme,
            [Uri]::UriSchemeHttps,
            [StringComparison]::OrdinalIgnoreCase
        ) -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or
        -not [string]::IsNullOrEmpty($uri.UserInfo)) {
        throw 'PrivacyPolicyUrl 必须是不含用户凭据的有效 HTTPS URL。'
    }
    return $candidate
}

function Get-ReleaseMutexName {
    param([Parameter(Mandatory)][string]$ProjectRoot)

    $normalizedRoot = [IO.Path]::GetFullPath($ProjectRoot).ToUpperInvariant()
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalizedRoot))
    } finally {
        $sha256.Dispose()
    }
    $hashText = ([BitConverter]::ToString($hash)).Replace('-', '')
    return "Local\VoluStep-Release-$hashText"
}

function Restore-ProcessEnvironment {
    param(
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][hashtable]$PreviousValues
    )

    foreach ($name in $Names) {
        [Environment]::SetEnvironmentVariable($name, $PreviousValues[$name], 'Process')
    }
}

function Remove-OwnedStagingDirectory {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ExpectedParent
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullParent = [IO.Path]::GetFullPath($ExpectedParent)
    $actualParent = [IO.Path]::GetDirectoryName($fullPath)
    if (-not [string]::Equals(
        $actualParent,
        $fullParent,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw "拒绝清理预期目录之外的 staging：$fullPath"
    }
    if (-not [IO.Directory]::Exists($fullPath)) {
        return
    }
    $item = Get-Item -LiteralPath $fullPath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "拒绝递归清理重解析点 staging：$fullPath"
    }
    [IO.Directory]::Delete($fullPath, $true)
}

$profile = switch ($SigningProfile) {
    'AppSigning' {
        [PSCustomObject]@{
            Label = '跨商店应用签名密钥'
            DefaultFile = 'volustep-app-signing.p12'
            DefaultAlias = 'volustep-app-signing'
            ArtifactDirectory = 'app-signing'
        }
    }
    'PlayUpload' {
        [PSCustomObject]@{
            Label = 'Google Play 独立上传密钥'
            DefaultFile = 'volustep-play-upload.p12'
            DefaultAlias = 'volustep-play-upload'
            ArtifactDirectory = 'play-upload'
        }
    }
}

if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Join-Path $script:ProjectRoot "keystore\$($profile.DefaultFile)"
}
if ([string]::IsNullOrWhiteSpace($KeyAlias)) {
    $KeyAlias = $profile.DefaultAlias
}
if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw "未找到$($profile.Label)：$KeystorePath。请先运行 scripts/create-release-keystore.ps1。"
}
$resolvedKeystorePath = (Resolve-Path -LiteralPath $KeystorePath).Path

$expectedFingerprint = Normalize-Sha256Fingerprint -Value $ExpectedCertificateSha256
$PrivacyPolicyUrl = Resolve-HttpsPrivacyPolicyUrl -Value $PrivacyPolicyUrl

$bundletoolPath = Join-Path $script:ProjectRoot '.toolchains\bundletool\bundletool-all-1.18.3.jar'
$bundletoolExpectedSha256 = 'A099CFA1543F55593BC2ED16A70A7C67FE54B1747BB7301F37FDFD6D91028E29'
if (-not (Test-Path -LiteralPath $bundletoolPath -PathType Leaf)) {
    throw "未找到 bundletool 1.18.3：$bundletoolPath。请先运行 scripts/install-bundletool.ps1。"
}
$bundletoolActualSha256 = (Get-FileHash -LiteralPath $bundletoolPath -Algorithm SHA256).Hash.ToUpperInvariant()
if ($bundletoolActualSha256 -cne $bundletoolExpectedSha256) {
    throw "bundletool SHA-256 不符。期望：$bundletoolExpectedSha256；实际：$bundletoolActualSha256"
}

$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) {
    throw "未找到 keytool：$keytool"
}

$signingVariables = @(
    'VOLUME_MAPPER_KEYSTORE',
    'VOLUME_MAPPER_STORE_PASSWORD',
    'VOLUME_MAPPER_KEY_ALIAS',
    'VOLUME_MAPPER_KEY_PASSWORD',
    'VOLUME_MAPPER_PRIVACY_POLICY_URL'
)
$previousValues = @{}
foreach ($name in $signingVariables) {
    $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$releaseMutex = [Threading.Mutex]::new(
    $false,
    (Get-ReleaseMutexName -ProjectRoot $script:ProjectRoot)
)
$mutexAcquired = $false
$securePassword = $null
$credential = $null
$plainPassword = $null

try {
    try {
        $mutexAcquired = $releaseMutex.WaitOne(0)
    } catch [Threading.AbandonedMutexException] {
        $mutexAcquired = $true
    }
    if (-not $mutexAcquired) {
        throw '另一个 Release 构建正在使用此工作区；本次构建未启动。'
    }

    $securePassword = Read-Host "请输入$($profile.Label)密码" -AsSecureString
    $credential = [PSCredential]::new('unused', $securePassword)
    $plainPassword = $credential.GetNetworkCredential().Password
    if ([string]::IsNullOrEmpty($plainPassword)) {
        throw '密码不能为空。'
    }

    try {
        [Environment]::SetEnvironmentVariable(
            'VOLUME_MAPPER_KEYSTORE',
            $resolvedKeystorePath,
            'Process'
        )
        [Environment]::SetEnvironmentVariable(
            'VOLUME_MAPPER_STORE_PASSWORD',
            $plainPassword,
            'Process'
        )
        [Environment]::SetEnvironmentVariable(
            'VOLUME_MAPPER_KEY_ALIAS',
            $KeyAlias,
            'Process'
        )
        [Environment]::SetEnvironmentVariable(
            'VOLUME_MAPPER_KEY_PASSWORD',
            $plainPassword,
            'Process'
        )
        [Environment]::SetEnvironmentVariable(
            'VOLUME_MAPPER_PRIVACY_POLICY_URL',
            $PrivacyPolicyUrl,
            'Process'
        )

        $certificateOutput = & $keytool `
            '-J-Duser.language=en' `
            '-J-Duser.country=US' `
            -list -v `
            -keystore $resolvedKeystorePath `
            -storetype PKCS12 `
            -storepass:env VOLUME_MAPPER_STORE_PASSWORD `
            -alias $KeyAlias 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "无法打开$($profile.Label)，请检查密码、文件和别名。"
        }
        $certificateText = ($certificateOutput | ForEach-Object { $_.ToString() }) -join "`n"
        $fingerprintMatch = [regex]::Match(
            $certificateText,
            'SHA256:\s*(?<Fingerprint>[0-9A-Fa-f:]{64,95})'
        )
        if (-not $fingerprintMatch.Success) {
            throw '无法解析签名证书的 SHA-256 指纹。'
        }
        $actualKeystoreFingerprint = Normalize-Sha256Fingerprint `
            -Value $fingerprintMatch.Groups['Fingerprint'].Value
        if ($actualKeystoreFingerprint -cne $expectedFingerprint) {
            throw "所选密钥库证书与离线记录不符。期望：$(Format-Sha256Fingerprint $expectedFingerprint)；实际：$(Format-Sha256Fingerprint $actualKeystoreFingerprint)"
        }

        Write-Host "签名配置：$($profile.Label)"
        Write-Host "证书 SHA-256：$(Format-Sha256Fingerprint $expectedFingerprint)"
        Write-Host "隐私政策：$PrivacyPolicyUrl"
        Write-Host '开始执行单元测试、Release Lint、APK 与 AAB 构建。'

        # --no-daemon 防止生产密码残留在长期运行的 Gradle daemon 环境中。
        & (Join-Path $script:ProjectRoot 'gradlew.bat') `
            --no-daemon `
            :app:testDebugUnitTest `
            :app:compileDebugAndroidTestKotlin `
            :app:lintRelease `
            :app:assembleRelease `
            :app:bundleRelease
        if ($LASTEXITCODE -ne 0) {
            throw "Release 构建失败，Gradle 退出码：$LASTEXITCODE"
        }
    } finally {
        Restore-ProcessEnvironment -Names $signingVariables -PreviousValues $previousValues
        $plainPassword = $null
        $credential = $null
        $securePassword = $null
    }

    $apkPath = Join-Path $script:ProjectRoot 'app\build\outputs\apk\release\app-release.apk'
    $aabPath = Join-Path $script:ProjectRoot 'app\build\outputs\bundle\release\app-release.aab'
    & (Join-Path $PSScriptRoot 'verify-release-artifacts.ps1') `
        -ApkPath $apkPath `
        -AabPath $aabPath `
        -ExpectedCertificateSha256 $expectedFingerprint `
        -ExpectedPrivacyPolicyUrl $PrivacyPolicyUrl

    $buildScriptPath = Join-Path $script:ProjectRoot 'app\build.gradle.kts'
    $buildScriptText = [IO.File]::ReadAllText($buildScriptPath, [Text.Encoding]::UTF8)
    $versionNameMatch = [regex]::Match(
        $buildScriptText,
        '(?m)^\s*versionName\s*=\s*"(?<Value>[^"]+)"'
    )
    $versionCodeMatch = [regex]::Match(
        $buildScriptText,
        '(?m)^\s*versionCode\s*=\s*(?<Value>\d+)'
    )
    if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
        throw '无法从 app/build.gradle.kts 读取版本号。'
    }
    $versionName = $versionNameMatch.Groups['Value'].Value
    $versionCode = $versionCodeMatch.Groups['Value'].Value
    $safeVersionName = $versionName -replace '[^0-9A-Za-z._-]', '_'
    $releaseVersionDirectory = Join-Path $script:ProjectRoot (
        "artifacts\release\$safeVersionName-$versionCode"
    )
    [IO.Directory]::CreateDirectory($releaseVersionDirectory) | Out-Null
    $artifactDirectory = Join-Path $releaseVersionDirectory $profile.ArtifactDirectory
    if (Test-Path -LiteralPath $artifactDirectory) {
        throw "正式产物目录已存在，不会覆盖：$artifactDirectory"
    }

    $stagingDirectory = Join-Path $releaseVersionDirectory (
        ".$($profile.ArtifactDirectory).staging-$PID-$([Guid]::NewGuid().ToString('N'))"
    )
    $stagingExists = $false
    try {
        [IO.Directory]::CreateDirectory($stagingDirectory) | Out-Null
        $stagingExists = $true
        $stagingItem = Get-Item -LiteralPath $stagingDirectory -Force
        if (($stagingItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "拒绝使用重解析点 staging：$stagingDirectory"
        }

        $artifactBaseName = "VoluStep-$safeVersionName-$versionCode-$($profile.ArtifactDirectory)"
        $stagedAab = Join-Path $stagingDirectory "$artifactBaseName.aab"
        [IO.File]::Copy($aabPath, $stagedAab, $false)
        if ($SigningProfile -eq 'AppSigning') {
            $stagedApk = Join-Path $stagingDirectory "$artifactBaseName.apk"
            [IO.File]::Copy($apkPath, $stagedApk, $false)
        }

        # 同目录 Directory.Move 不覆盖既有目标，并在本卷内原子发布整套产物。
        [IO.Directory]::Move($stagingDirectory, $artifactDirectory)
        $stagingExists = $false
    } finally {
        if ($stagingExists) {
            Remove-OwnedStagingDirectory `
                -Path $stagingDirectory `
                -ExpectedParent $releaseVersionDirectory
        }
    }

    Write-Host "正式产物已归档：$artifactDirectory"
    if ($SigningProfile -eq 'PlayUpload') {
        Write-Host '只归档了供 Google Play 上传的 AAB；本次 APK 使用上传密钥签名，不得向用户或其他商店分发。'
    } else {
        Write-Host '该 APK/AAB 使用统一应用签名密钥；启用独立 Play 上传密钥后，不要把此 AAB 当作常规 Play 更新包。'
    }
} finally {
    try {
        Restore-ProcessEnvironment -Names $signingVariables -PreviousValues $previousValues
        $plainPassword = $null
        $credential = $null
        $securePassword = $null
    } finally {
        try {
            if ($mutexAcquired) {
                $releaseMutex.ReleaseMutex()
            }
        } finally {
            $releaseMutex.Dispose()
        }
    }
}
