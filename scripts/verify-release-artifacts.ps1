[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ApkPath,
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$AabPath,
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ExpectedCertificateSha256,
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ExpectedPrivacyPolicyUrl,
    [string]$ExpectedPackageName,
    [string]$ExpectedVersionCode,
    [string]$ExpectedVersionName
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'android-env.ps1')

$bundletoolVersion = '1.18.3'
$bundletoolExpectedSha256 = 'A099CFA1543F55593BC2ED16A70A7C67FE54B1747BB7301F37FDFD6D91028E29'
$bundletool = Join-Path $script:ProjectRoot (
    ".toolchains\bundletool\bundletool-all-$bundletoolVersion.jar"
)
if (-not (Test-Path -LiteralPath $bundletool -PathType Leaf)) {
    throw "未找到 bundletool $bundletoolVersion：$bundletool。请先运行 scripts/install-bundletool.ps1。"
}
$bundletoolActualSha256 = (Get-FileHash -LiteralPath $bundletool -Algorithm SHA256).Hash.ToUpperInvariant()
if ($bundletoolActualSha256 -cne $bundletoolExpectedSha256) {
    throw "bundletool SHA-256 不符。期望：$bundletoolExpectedSha256；实际：$bundletoolActualSha256"
}
$privacyMetadataName = 'dev.spcdts.volumemapper.PRIVACY_POLICY_URL'

function Resolve-ArtifactPath {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "未找到$Label：$Path"
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    if ((Get-Item -LiteralPath $resolved).Length -le 0) {
        throw "$Label 是空文件：$resolved"
    }
    return $resolved
}

function Invoke-CheckedTool {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$FailureMessage
    )

    $output = & $FilePath @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    if ($exitCode -ne 0) {
        throw "$FailureMessage（退出码 $exitCode）`n$text"
    }
    return $text
}

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
        throw 'ExpectedPrivacyPolicyUrl 必须是不含用户凭据的有效 HTTPS URL。'
    }
    return $candidate
}

function Get-ApkManifestMetadataValue {
    param(
        [Parameter(Mandatory)][string]$XmlTreeText,
        [Parameter(Mandatory)][string]$MetadataName
    )

    $lines = @($XmlTreeText -split "`r?`n")
    $values = @()
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $elementMatch = [regex]::Match($lines[$index], '^(?<Indent>\s*)E:\s+meta-data\b')
        if (-not $elementMatch.Success) { continue }

        $elementIndent = $elementMatch.Groups['Indent'].Value.Length
        $block = @($lines[$index])
        for ($next = $index + 1; $next -lt $lines.Count; $next++) {
            $nextElementMatch = [regex]::Match($lines[$next], '^(?<Indent>\s*)E:\s+')
            if ($nextElementMatch.Success -and
                $nextElementMatch.Groups['Indent'].Value.Length -le $elementIndent) {
                break
            }
            $block += $lines[$next]
        }
        $blockText = $block -join "`n"
        $nameMatch = [regex]::Match(
            $blockText,
            '(?m)^\s*A:\s+\S*:name(?:\([^)]*\))?="[^"]*"\s+\(Raw:\s+"(?<Value>[^"]*)"\)\s*$'
        )
        if (-not $nameMatch.Success -or $nameMatch.Groups['Value'].Value -cne $MetadataName) {
            continue
        }
        $valueMatch = [regex]::Match(
            $blockText,
            '(?m)^\s*A:\s+\S*:value(?:\([^)]*\))?="[^"]*"\s+\(Raw:\s+"(?<Value>[^"]*)"\)\s*$'
        )
        if (-not $valueMatch.Success) {
            throw "APK manifest 的 $MetadataName metadata 缺少文本 value。"
        }
        $values += $valueMatch.Groups['Value'].Value
    }
    if ($values.Count -ne 1) {
        throw "APK manifest 必须恰好包含一个 $MetadataName metadata；实际：$($values.Count) 个。"
    }
    return $values[0]
}

function ConvertFrom-SafeXml {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$FailureMessage
    )

    $manifestStart = $Value.IndexOf('<manifest', [StringComparison]::Ordinal)
    $manifestEnd = $Value.LastIndexOf('</manifest>', [StringComparison]::Ordinal)
    if ($manifestStart -lt 0 -or $manifestEnd -lt $manifestStart) {
        throw $FailureMessage
    }
    $manifestEnd += '</manifest>'.Length
    $xmlText = $Value.Substring($manifestStart, $manifestEnd - $manifestStart)
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $stringReader = [IO.StringReader]::new($xmlText)
    $xmlReader = $null
    try {
        $xmlReader = [Xml.XmlReader]::Create($stringReader, $settings)
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($xmlReader)
        return $document
    } catch {
        throw "$FailureMessage`n$($_.Exception.Message)"
    } finally {
        if ($null -ne $xmlReader) { $xmlReader.Dispose() }
        $stringReader.Dispose()
    }
}

function Get-VersionSortKey {
    param([Parameter(Mandatory)][string]$Name)

    $match = [regex]::Match($Name, '^(?<Version>\d+(?:\.\d+){1,3})')
    if ($match.Success) {
        return [Version]$match.Groups['Version'].Value
    }
    return [Version]'0.0'
}

$resolvedApkPath = Resolve-ArtifactPath -Path $ApkPath -Label 'Release APK'
$resolvedAabPath = Resolve-ArtifactPath -Path $AabPath -Label 'Release AAB'
$expectedFingerprint = Normalize-Sha256Fingerprint -Value $ExpectedCertificateSha256
$expectedPrivacyPolicyUrl = Resolve-HttpsPrivacyPolicyUrl -Value $ExpectedPrivacyPolicyUrl

$buildToolsRoot = Join-Path $env:ANDROID_HOME 'build-tools'
$isWindowsPlatform = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$apksignerName = if ($isWindowsPlatform) { 'apksigner.bat' } else { 'apksigner' }
$zipalignName = if ($isWindowsPlatform) { 'zipalign.exe' } else { 'zipalign' }
$aapt2Name = if ($isWindowsPlatform) { 'aapt2.exe' } else { 'aapt2' }
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Sort-Object @{ Expression = { Get-VersionSortKey -Name $_.Name }; Descending = $true }
$selectedBuildTools = $buildTools | Where-Object {
    (Test-Path -LiteralPath (Join-Path $_.FullName $apksignerName) -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $_.FullName $zipalignName) -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $_.FullName $aapt2Name) -PathType Leaf)
} | Select-Object -First 1
if ($null -eq $selectedBuildTools) {
    throw "Android SDK 中没有同时包含 apksigner、zipalign 和 aapt2 的 Build Tools：$buildToolsRoot"
}

$apksigner = Join-Path $selectedBuildTools.FullName $apksignerName
$zipalign = Join-Path $selectedBuildTools.FullName $zipalignName
$aapt2 = Join-Path $selectedBuildTools.FullName $aapt2Name
$jarsignerName = if ($isWindowsPlatform) { 'jarsigner.exe' } else { 'jarsigner' }
$keytoolName = if ($isWindowsPlatform) { 'keytool.exe' } else { 'keytool' }
$javaName = if ($isWindowsPlatform) { 'java.exe' } else { 'java' }
$jarsigner = Join-Path $env:JAVA_HOME "bin\$jarsignerName"
$keytool = Join-Path $env:JAVA_HOME "bin\$keytoolName"
$java = Join-Path $env:JAVA_HOME "bin\$javaName"
foreach ($javaTool in @($java, $jarsigner, $keytool)) {
    if (-not (Test-Path -LiteralPath $javaTool -PathType Leaf)) {
        throw "未找到 Java 发布工具：$javaTool"
    }
}

$buildScriptPath = Join-Path $script:ProjectRoot 'app\build.gradle.kts'
$buildScriptText = [IO.File]::ReadAllText($buildScriptPath, [Text.Encoding]::UTF8)
$identityPatterns = @{
    PackageName = '(?m)^\s*applicationId\s*=\s*"(?<Value>[^"]+)"'
    VersionCode = '(?m)^\s*versionCode\s*=\s*(?<Value>\d+)'
    VersionName = '(?m)^\s*versionName\s*=\s*"(?<Value>[^"]+)"'
}
$match = [regex]::Match($buildScriptText, $identityPatterns.PackageName)
if (-not $match.Success) { throw '无法从 app/build.gradle.kts 读取 applicationId。' }
$gradlePackageName = $match.Groups['Value'].Value
$match = [regex]::Match($buildScriptText, $identityPatterns.VersionCode)
if (-not $match.Success) { throw '无法从 app/build.gradle.kts 读取 versionCode。' }
$gradleVersionCode = $match.Groups['Value'].Value
$match = [regex]::Match($buildScriptText, $identityPatterns.VersionName)
if (-not $match.Success) { throw '无法从 app/build.gradle.kts 读取 versionName。' }
$gradleVersionName = $match.Groups['Value'].Value

if ([string]::IsNullOrWhiteSpace($ExpectedPackageName)) {
    $ExpectedPackageName = $gradlePackageName
} elseif ($ExpectedPackageName -cne $gradlePackageName) {
    throw "期望包名与 Gradle 不符。参数：$ExpectedPackageName；Gradle：$gradlePackageName"
}
if ([string]::IsNullOrWhiteSpace($ExpectedVersionCode)) {
    $ExpectedVersionCode = $gradleVersionCode
} elseif ($ExpectedVersionCode -cne $gradleVersionCode) {
    throw "期望 versionCode 与 Gradle 不符。参数：$ExpectedVersionCode；Gradle：$gradleVersionCode"
}
if ([string]::IsNullOrWhiteSpace($ExpectedVersionName)) {
    $ExpectedVersionName = $gradleVersionName
} elseif ($ExpectedVersionName -cne $gradleVersionName) {
    throw "期望 versionName 与 Gradle 不符。参数：$ExpectedVersionName；Gradle：$gradleVersionName"
}

[void](Invoke-CheckedTool `
    -FilePath $zipalign `
    -Arguments @('-c', '-P', '16', '-v', '4', $resolvedApkPath) `
    -FailureMessage 'Release APK 未通过 16 KiB/4 字节 zipalign 核验')

$apkSignatureText = Invoke-CheckedTool `
    -FilePath $apksigner `
    -Arguments @('verify', '-Werr', '--verbose', '--print-certs', $resolvedApkPath) `
    -FailureMessage 'Release APK 签名无效或未签名'
$apkFingerprintMatches = [regex]::Matches(
    $apkSignatureText,
    '(?im)^(?:Signer #\d+|V\d+(?:\.\d+)? Signer): certificate SHA-256 digest:\s*(?<Fingerprint>[0-9A-Fa-f]{64})\s*$'
)
if ($apkFingerprintMatches.Count -eq 0) {
    throw 'Release APK 虽通过 apksigner，但没有可解析的签名证书；按未签名处理。'
}
$apkFingerprints = @($apkFingerprintMatches | ForEach-Object {
    Normalize-Sha256Fingerprint -Value $_.Groups['Fingerprint'].Value
} | Select-Object -Unique)
if ($apkFingerprints.Count -ne 1) {
    $actual = ($apkFingerprints | ForEach-Object {
        Format-Sha256Fingerprint -Value $_
    }) -join ', '
    throw "Release APK 必须恰好包含一个唯一签名证书；实际：$actual"
}
if ($apkFingerprints[0] -cne $expectedFingerprint) {
    throw "Release APK 证书指纹不符。期望：$(Format-Sha256Fingerprint $expectedFingerprint)；实际：$(Format-Sha256Fingerprint $apkFingerprints[0])"
}

$badgingText = Invoke-CheckedTool `
    -FilePath $aapt2 `
    -Arguments @('dump', 'badging', $resolvedApkPath) `
    -FailureMessage '无法读取 Release APK 包信息'
$packageLine = ($badgingText -split "`r?`n" | Where-Object { $_ -match '^package:' } |
    Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($packageLine)) {
    throw 'aapt2 输出中没有 package 信息。'
}
$packageMatch = [regex]::Match($packageLine, "name='(?<Value>[^']+)'")
$versionCodeMatch = [regex]::Match($packageLine, "versionCode='(?<Value>[^']+)'")
$versionNameMatch = [regex]::Match($packageLine, "versionName='(?<Value>[^']*)'")
if (-not $packageMatch.Success -or -not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw "无法解析 APK 包名或版本：$packageLine"
}
$actualPackageName = $packageMatch.Groups['Value'].Value
$actualVersionCode = $versionCodeMatch.Groups['Value'].Value
$actualVersionName = $versionNameMatch.Groups['Value'].Value
if ($actualPackageName -cne $ExpectedPackageName) {
    throw "APK 包名不符。期望：$ExpectedPackageName；实际：$actualPackageName"
}
if ($actualVersionCode -cne $ExpectedVersionCode) {
    throw "APK versionCode 不符。期望：$ExpectedVersionCode；实际：$actualVersionCode"
}
if ($actualVersionName -cne $ExpectedVersionName) {
    throw "APK versionName 不符。期望：$ExpectedVersionName；实际：$actualVersionName"
}

$apkManifestTreeText = Invoke-CheckedTool `
    -FilePath $aapt2 `
    -Arguments @('dump', 'xmltree', $resolvedApkPath, '--file', 'AndroidManifest.xml') `
    -FailureMessage '无法读取 Release APK manifest'
$actualApkPrivacyPolicyUrl = Get-ApkManifestMetadataValue `
    -XmlTreeText $apkManifestTreeText `
    -MetadataName $privacyMetadataName
if ($actualApkPrivacyPolicyUrl -cne $expectedPrivacyPolicyUrl) {
    throw "APK 隐私政策 URL 不符或为空。期望：$expectedPrivacyPolicyUrl；实际：$actualApkPrivacyPolicyUrl"
}

$aabManifestText = Invoke-CheckedTool `
    -FilePath $java `
    -Arguments @(
        '-jar',
        $bundletool,
        'dump',
        'manifest',
        "--bundle=$resolvedAabPath"
    ) `
    -FailureMessage 'bundletool 无法读取 Release AAB manifest'
$aabManifestDocument = ConvertFrom-SafeXml `
    -Value $aabManifestText `
    -FailureMessage '无法解析 bundletool 输出的 Release AAB manifest'
$aabManifest = $aabManifestDocument.DocumentElement
if ($null -eq $aabManifest -or $aabManifest.LocalName -cne 'manifest') {
    throw 'Release AAB manifest 缺少 manifest 根元素。'
}
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$actualAabPackageName = $aabManifest.GetAttribute('package')
$actualAabVersionCode = $aabManifest.GetAttribute('versionCode', $androidNamespace)
$actualAabVersionName = $aabManifest.GetAttribute('versionName', $androidNamespace)
if ([string]::IsNullOrWhiteSpace($actualAabPackageName) -or
    [string]::IsNullOrWhiteSpace($actualAabVersionCode) -or
    [string]::IsNullOrWhiteSpace($actualAabVersionName)) {
    throw 'Release AAB manifest 缺少包名、versionCode 或 versionName。'
}
if ($actualAabPackageName -cne $ExpectedPackageName) {
    throw "AAB 包名不符。期望：$ExpectedPackageName；实际：$actualAabPackageName"
}
if ($actualAabVersionCode -cne $ExpectedVersionCode) {
    throw "AAB versionCode 不符。期望：$ExpectedVersionCode；实际：$actualAabVersionCode"
}
if ($actualAabVersionName -cne $ExpectedVersionName) {
    throw "AAB versionName 不符。期望：$ExpectedVersionName；实际：$actualAabVersionName"
}
$namespaceManager = [Xml.XmlNamespaceManager]::new($aabManifestDocument.NameTable)
$namespaceManager.AddNamespace('android', $androidNamespace)
$aabPrivacyMetadataNodes = @($aabManifestDocument.SelectNodes(
    '/manifest/application/meta-data[@android:name="dev.spcdts.volumemapper.PRIVACY_POLICY_URL"]',
    $namespaceManager
))
if ($aabPrivacyMetadataNodes.Count -ne 1) {
    throw "AAB manifest 必须恰好包含一个 $privacyMetadataName metadata；实际：$($aabPrivacyMetadataNodes.Count) 个。"
}
$actualAabPrivacyPolicyUrl = $aabPrivacyMetadataNodes[0].GetAttribute(
    'value',
    $androidNamespace
)
if ($actualAabPrivacyPolicyUrl -cne $expectedPrivacyPolicyUrl) {
    throw "AAB 隐私政策 URL 不符或为空。期望：$expectedPrivacyPolicyUrl；实际：$actualAabPrivacyPolicyUrl"
}
if ($actualAabPackageName -cne $actualPackageName -or
    $actualAabVersionCode -cne $actualVersionCode -or
    $actualAabVersionName -cne $actualVersionName) {
    throw 'Release APK 与 AAB 的包名或版本不一致。'
}

$aabSignatureText = Invoke-CheckedTool `
    -FilePath $jarsigner `
    -Arguments @(
        '-J-Duser.language=en',
        '-J-Duser.country=US',
        '-verify',
        '-verbose',
        '-certs',
        $resolvedAabPath
    ) `
    -FailureMessage 'Release AAB JAR 签名无效或未签名'
if ($aabSignatureText -match '(?im)jar is unsigned|unsigned entries' -or
    $aabSignatureText -notmatch '(?im)^\s*jar verified\.\s*$') {
    throw 'Release AAB 没有完整、有效的 JAR 签名。'
}

$aabCertificateText = Invoke-CheckedTool `
    -FilePath $keytool `
    -Arguments @(
        '-J-Duser.language=en',
        '-J-Duser.country=US',
        '-printcert',
        '-jarfile',
        $resolvedAabPath
    ) `
    -FailureMessage '无法读取 Release AAB 签名证书'
$aabFingerprintMatches = [regex]::Matches(
    $aabCertificateText,
    'SHA256:\s*(?<Fingerprint>[0-9A-Fa-f:]{64,95})'
)
if ($aabFingerprintMatches.Count -eq 0) {
    throw 'Release AAB 没有可解析的 SHA-256 签名证书指纹；按未签名处理。'
}
$aabFingerprints = @($aabFingerprintMatches | ForEach-Object {
    Normalize-Sha256Fingerprint -Value $_.Groups['Fingerprint'].Value
} | Select-Object -Unique)
if ($aabFingerprints.Count -ne 1) {
    $actual = ($aabFingerprints | ForEach-Object {
        Format-Sha256Fingerprint -Value $_
    }) -join ', '
    throw "Release AAB 必须恰好包含一个唯一签名证书；实际：$actual"
}
$aabFingerprint = $aabFingerprints[0]
if ($aabFingerprint -cne $expectedFingerprint) {
    throw "Release AAB 证书指纹不符。期望：$(Format-Sha256Fingerprint $expectedFingerprint)；实际：$(Format-Sha256Fingerprint $aabFingerprint)"
}

$apkHash = (Get-FileHash -LiteralPath $resolvedApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
$aabHash = (Get-FileHash -LiteralPath $resolvedAabPath -Algorithm SHA256).Hash.ToUpperInvariant()

Write-Host 'Release 产物核验通过。'
Write-Host "包名：$actualPackageName"
Write-Host "版本：$actualVersionName（$actualVersionCode）"
Write-Host "隐私政策：$expectedPrivacyPolicyUrl"
Write-Host "证书 SHA-256：$(Format-Sha256Fingerprint $expectedFingerprint)"
Write-Host "APK SHA-256：$apkHash"
Write-Host "AAB SHA-256：$aabHash"
