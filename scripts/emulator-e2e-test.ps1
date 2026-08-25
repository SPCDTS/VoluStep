[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554',
    [switch]$SkipBuild,
    [ValidateSet('en-US', 'zh-CN')]
    [string]$AppLocale = 'en-US'
)

. (Join-Path $PSScriptRoot 'android-env.ps1')

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$packageName = 'dev.spcdts.volumemapper.debug'
$testPackageName = 'dev.spcdts.volumemapper.debug.test'
$activityComponent = "$packageName/dev.spcdts.volumemapper.MainActivity"
$accessibilityComponent = "$packageName/dev.spcdts.volumemapper.runtime.VolumeKeyAccessibilityService"
$runnerComponent = "$testPackageName/androidx.test.runner.AndroidJUnitRunner"
$fixtureTest = 'dev.spcdts.volumemapper.RealSystemVolumeE2eTest#disclosureAccessibilityControllerAndSilentForegroundStop'
$remoteWindowDump = '/sdcard/volumemapper-e2e-window.xml'
$localWindowDump = Join-Path ([IO.Path]::GetTempPath()) "volumemapper-e2e-$PID.xml"

if ($Serial -notmatch '^emulator-\d+$') {
    throw '此脚本会清空应用数据并修改无障碍设置，只允许显式指定 emulator-* 序列号。'
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = & $adb -s $Serial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $message = [string]::Join([Environment]::NewLine, [string[]]$output)
        throw "adb 命令失败（exit=$exitCode）：adb -s $Serial $($Arguments -join ' ')`n$message"
    }
    return $output
}

function Wait-ForCondition {
    param(
        [scriptblock]$Condition,
        [string]$FailureMessage,
        [int]$TimeoutSeconds = 15
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw $FailureMessage
}

function Get-InstrumentationStatusValue {
    param(
        [string]$Output,
        [string]$Key
    )

    $escapedKey = [regex]::Escape($Key)
    $match = [regex]::Match(
        $Output,
        "(?m)^INSTRUMENTATION_STATUS:\s+${escapedKey}=(.*)\r?$"
    )
    if (-not $match.Success) {
        throw "Instrumentation 未返回资源契约：$Key"
    }
    return $match.Groups[1].Value.Trim()
}

function Invoke-CleanupStep {
    param(
        [string]$Description,
        [scriptblock]$Action
    )

    try {
        & $Action
    } catch {
        $script:cleanupErrors.Add("${Description}：$($_.Exception.Message)") | Out-Null
    }
}

function Get-StreamVolume {
    param([int]$Stream)

    if ($script:volumeShell -eq 'legacy') {
        $lines = Invoke-Adb shell media volume --stream $Stream --get
    } else {
        $lines = Invoke-Adb shell cmd media_session volume --stream $Stream --get
    }
    $output = [string]::Join([Environment]::NewLine, [string[]]$lines)
    $match = [regex]::Match($output, 'volume is\s+(\d+)\s+in range \[(\d+)\.\.(\d+)\]')
    if (-not $match.Success -and $null -eq $script:volumeShell) {
        $legacyLines = Invoke-Adb shell media volume --stream $Stream --get
        $legacyOutput = [string]::Join([Environment]::NewLine, [string[]]$legacyLines)
        $match = [regex]::Match(
            $legacyOutput,
            'volume is\s+(\d+)\s+in range \[(\d+)\.\.(\d+)\]'
        )
        if ($match.Success) {
            $script:volumeShell = 'legacy'
            $output = $legacyOutput
        }
    } elseif ($match.Success -and $null -eq $script:volumeShell) {
        $script:volumeShell = 'modern'
    }
    if (-not $match.Success) {
        throw "无法解析媒体音量：$output"
    }
    return [pscustomobject]@{
        Current = [int]$match.Groups[1].Value
        Minimum = [int]$match.Groups[2].Value
        Maximum = [int]$match.Groups[3].Value
    }
}

function Get-MediaVolume {
    return Get-StreamVolume -Stream 3
}

function Set-StreamVolume {
    param(
        [int]$Stream,
        [int]$Index
    )

    if ($null -eq $script:volumeShell) { Get-StreamVolume -Stream $Stream | Out-Null }
    if ($script:volumeShell -eq 'legacy') {
        Invoke-Adb shell media volume --stream $Stream --set $Index | Out-Null
    } else {
        Invoke-Adb shell cmd media_session volume --stream $Stream --set $Index | Out-Null
    }
    Wait-ForCondition -FailureMessage "音频流 $Stream 的音量未能设置为 ${Index}。" -Condition {
        (Get-StreamVolume -Stream $Stream).Current -eq $Index
    }
}

function Set-MediaVolume {
    param([int]$Index)

    Set-StreamVolume -Stream 3 -Index $Index
}

function Get-WindowXml {
    Invoke-Adb shell uiautomator dump --compressed $remoteWindowDump | Out-Null
    Invoke-Adb pull $remoteWindowDump $localWindowDump | Out-Null
    $document = [System.Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $false
    $document.Load($localWindowDump)
    return $document
}

function Find-UiNodeByText {
    param(
        [System.Xml.XmlDocument]$Document,
        [string]$Text
    )

    foreach ($node in $Document.SelectNodes('//node')) {
        $visibleText = $node.GetAttribute('text')
        $description = $node.GetAttribute('content-desc')
        if (
            $visibleText.IndexOf($Text, [StringComparison]::Ordinal) -ge 0 -or
            $description.IndexOf($Text, [StringComparison]::Ordinal) -ge 0
        ) {
            return $node
        }
    }
    return $null
}

function Assert-NotificationAbsentFromShade {
    param([string]$Title)

    Invoke-Adb shell cmd statusbar expand-notifications | Out-Null
    try {
        $script:shadeDocument = $null
        Wait-ForCondition `
            -TimeoutSeconds 8 `
            -FailureMessage '通知抽屉未成功展开，无法验证静默前台服务。' `
            -Condition {
                try {
                    $candidate = Get-WindowXml
                    $shadeMarker = $candidate.SelectNodes('//node') | Where-Object {
                        $resourceId = $_.GetAttribute('resource-id')
                        $resourceId.EndsWith(':id/notification_panel') -or
                            $resourceId.EndsWith(':id/notification_stack_scroller') -or
                            $resourceId.EndsWith(':id/quick_settings_panel') -or
                            $resourceId.EndsWith(':id/split_shade_status_bar') -or
                            $resourceId.EndsWith(':id/dismiss_text')
                    } | Select-Object -First 1
                    if ($null -ne $shadeMarker) {
                        $script:shadeDocument = $candidate
                        return $true
                    }
                } catch {
                    return $false
                }
                return $false
            }
        $document = $script:shadeDocument
        if ($null -ne (Find-UiNodeByText -Document $document -Text $Title)) {
            throw "静默前台服务仍显示在普通通知抽屉：${Title}"
        }
    } finally {
        Invoke-Adb shell cmd statusbar collapse | Out-Null
    }
}

function Get-AppTaskId {
    $dump = [string]::Join(
        [Environment]::NewLine,
        [string[]](Invoke-Adb shell dumpsys activity activities)
    )
    $escapedPackage = [regex]::Escape($packageName)
    $match = [regex]::Match(
        $dump,
        "\* Task\{[^\r\n]*#(\d+)[^\r\n]*A=\d+:${escapedPackage}(?:\s|$)"
    )
    if (-not $match.Success) { return $null }
    return [int]$match.Groups[1].Value
}

function Remove-AppTaskFromRecents {
    $originalTaskId = Get-AppTaskId
    if ($null -eq $originalTaskId) {
        throw '进入最近任务前未找到本应用 task，无法验证划卡生命周期。'
    }

    Invoke-Adb shell input keyevent KEYCODE_APP_SWITCH | Out-Null
    Start-Sleep -Milliseconds 800

    $sizeOutput = [string]::Join(
        [Environment]::NewLine,
        [string[]](Invoke-Adb shell wm size)
    )
    $sizeMatches = [regex]::Matches($sizeOutput, '(\d+)x(\d+)')
    if ($sizeMatches.Count -eq 0) {
        throw "无法解析模拟器屏幕尺寸：$sizeOutput"
    }
    $sizeMatch = $sizeMatches[$sizeMatches.Count - 1]
    $width = [int]$sizeMatch.Groups[1].Value
    $height = [int]$sizeMatch.Groups[2].Value
    $x = [int]($width / 2)
    $startY = [int]($height * 0.55)
    $endY = [int]($height * 0.08)
    Invoke-Adb shell input swipe $x $startY $x $endY 350 | Out-Null

    Wait-ForCondition -FailureMessage "最近任务卡片划掉后，task $originalTaskId 仍然存在。" -Condition {
        $currentTaskId = Get-AppTaskId
        $null -eq $currentTaskId -or $currentTaskId -ne $originalTaskId
    }
    Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
}

function Wait-ForUiText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = 15
    )

    $foundNode = $null
    Wait-ForCondition -TimeoutSeconds $TimeoutSeconds -FailureMessage "界面中未找到文本：${Text}" -Condition {
        try {
            $script:foundNode = Find-UiNodeByText -Document (Get-WindowXml) -Text $Text
            return $null -ne $script:foundNode
        } catch {
            return $false
        }
    }
    return $script:foundNode
}

function Get-UiTapPointByText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = 15,
        [switch]$AllowNonClickable
    )

    $matchedNode = Wait-ForUiText -Text $Text -TimeoutSeconds $TimeoutSeconds
    $node = $matchedNode
    while ($null -ne $node -and $node.GetAttribute('clickable') -ne 'true') {
        $node = $node.ParentNode
        if ($node -isnot [System.Xml.XmlElement]) { break }
    }
    if ($node -isnot [System.Xml.XmlElement]) {
        if (-not $AllowNonClickable) {
            throw "文本【${Text}】没有可点击的父节点。"
        }
        $node = $matchedNode
    }

    $match = [regex]::Match($node.GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) {
        throw "无法解析【${Text}】的控件边界：$($node.GetAttribute('bounds'))"
    }
    $left = [int]$match.Groups[1].Value
    $top = [int]$match.Groups[2].Value
    $right = [int]$match.Groups[3].Value
    $bottom = [int]$match.Groups[4].Value
    $x = [int](($left + $right) / 2)
    $y = [int](($top + $bottom) / 2)
    return [pscustomobject]@{ X = $x; Y = $y }
}

function Invoke-UiTapPoint {
    param([pscustomobject]$Point)

    Invoke-Adb shell input tap $Point.X $Point.Y | Out-Null
}

function Invoke-UiTapText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = 15
    )

    Invoke-UiTapPoint -Point (
        Get-UiTapPointByText -Text $Text -TimeoutSeconds $TimeoutSeconds
    )
}

function Invoke-UiScrollForward {
    $displayInfo = [string]::Join(
        [Environment]::NewLine,
        [string[]](Invoke-Adb shell wm size)
    )
    $match = [regex]::Match($displayInfo, 'Override size:\s*(\d+)x(\d+)')
    if (-not $match.Success) {
        $match = [regex]::Match($displayInfo, 'Physical size:\s*(\d+)x(\d+)')
    }
    if (-not $match.Success) {
        throw "无法解析模拟器显示尺寸：$displayInfo"
    }
    $width = [int]$match.Groups[1].Value
    $height = [int]$match.Groups[2].Value
    $x = [int]($width / 2)
    $startY = [int]($height * 0.72)
    $endY = [int]($height * 0.32)
    Invoke-Adb shell input swipe $x $startY $x $endY 400 | Out-Null
    Start-Sleep -Milliseconds 300
}

function Get-UiTapPointByTextWithScroll {
    param(
        [string]$Text,
        [int]$MaximumSwipes = 4,
        [switch]$AllowNonClickable
    )

    for ($attempt = 0; $attempt -le $MaximumSwipes; $attempt++) {
        try {
            return Get-UiTapPointByText `
                -Text $Text `
                -TimeoutSeconds 2 `
                -AllowNonClickable:$AllowNonClickable
        } catch {
            if ($attempt -eq $MaximumSwipes) {
                throw "滚动 $MaximumSwipes 次后仍未找到界面文本：${Text}"
            }
            Invoke-UiScrollForward
        }
    }
}

function Send-EmulatorVolumeKey {
    param([ValidateSet('UP', 'DOWN')][string]$Direction)

    $keyCode = if ($Direction -eq 'UP') { 115 } else { 114 }
    # 直接写入模拟器 evdev，在内核层生成 Linux input event；adb shell input 与
    # UiAutomation 的注入路径会跳过 Accessibility input filter，不能用于本断言。
    Invoke-Adb shell sendevent $script:volumeInputDevice 1 $keyCode 1 | Out-Null
    Invoke-Adb shell sendevent $script:volumeInputDevice 0 0 0 | Out-Null
    Start-Sleep -Milliseconds 60
    Invoke-Adb shell sendevent $script:volumeInputDevice 1 $keyCode 0 | Out-Null
    Invoke-Adb shell sendevent $script:volumeInputDevice 0 0 0 | Out-Null
}

function Get-VolumeInputDevice {
    $devices = [string]::Join(
        [Environment]::NewLine,
        [string[]](Invoke-Adb shell getevent -lp)
    )
    foreach ($block in [regex]::Split($devices, '(?m)(?=^add device \d+:)')) {
        if ($block.IndexOf('KEY_VOLUMEUP', [StringComparison]::Ordinal) -lt 0) { continue }
        $pathMatch = [regex]::Match($block, '/dev/input/event\d+')
        if ($pathMatch.Success) { return $pathMatch.Value }
    }
    throw '未找到声明 KEY_VOLUMEUP 的模拟器 evdev 输入设备。'
}

function Get-AudioServiceDump {
    return [string]::Join(
        [Environment]::NewLine,
        [string[]](Invoke-Adb shell dumpsys audio)
    )
}

function Get-LatestAudioEventMarker {
    param(
        [string]$Dump,
        [string]$EventPattern
    )

    $expression =
        "(?m)^\s*(?<timestamp>\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3})\s+$EventPattern"
    $matches = [regex]::Matches($Dump, $expression)
    if ($matches.Count -eq 0) { return $null }
    return $matches[$matches.Count - 1].Groups['timestamp'].Value
}

function Test-AudioEventAdvanced {
    param(
        [AllowNull()][string]$Before,
        [AllowNull()][string]$After
    )

    if ([string]::IsNullOrEmpty($After)) { return $false }
    if ([string]::IsNullOrEmpty($Before)) { return $true }
    $format = 'MM-dd HH:mm:ss:fff'
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $beforeTime = [DateTime]::ParseExact($Before, $format, $culture)
    $afterTime = [DateTime]::ParseExact($After, $format, $culture)
    if ($afterTime -lt $beforeTime -and ($beforeTime - $afterTime).TotalDays -gt 300) {
        $afterTime = $afterTime.AddYears(1)
    }
    return $afterTime -gt $beforeTime
}

function Get-AppServicesDump {
    return [string]::Join(
        [Environment]::NewLine,
        [string[]](Invoke-Adb shell dumpsys activity services $packageName)
    )
}

function Test-AccessibilityServiceBound {
    $dump = Get-AppServicesDump
    return $dump.IndexOf('VolumeKeyAccessibilityService', [StringComparison]::Ordinal) -ge 0 -and
        $dump.IndexOf('hasBound=true', [StringComparison]::Ordinal) -ge 0
}

function Test-ControllerServiceRunning {
    $dump = Get-AppServicesDump
    return $dump.IndexOf('MappingControllerService', [StringComparison]::Ordinal) -ge 0 -and
        $dump.IndexOf('isForeground=true', [StringComparison]::Ordinal) -ge 0
}

function Enable-AdbRoot {
    $rootLines = & $adb -s $Serial root 2>&1
    $rootMessage = [string]::Join([Environment]::NewLine, [string[]]@($rootLines))
    # 某些镜像在重启 adbd 时会先关闭 transport，并让 `adb root` 返回 exit 1；
    # 最终 UID 才是可靠判据。
    & $adb -s $Serial wait-for-device 2>&1 | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $uid = (& $adb -s $Serial shell id -u 2>$null)
        if ($LASTEXITCODE -eq 0 -and ([string]$uid).Trim() -eq '0') { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "无法将模拟器 adbd 切换为 root：$rootMessage"
}

function Disable-AdbRoot {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        & $adb -s $Serial unroot 2>&1 | Out-Null
        Start-Sleep -Milliseconds 500
        & $adb -s $Serial wait-for-device 2>&1 | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        do {
            $uid = (& $adb -s $Serial shell id -u 2>$null)
            if ($LASTEXITCODE -eq 0 -and ([string]$uid).Trim() -ne '0') { return }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
    }
    throw '无法将模拟器 adbd 恢复为普通 shell。'
}

if (-not $SkipBuild) {
    & (Join-Path $script:ProjectRoot 'gradlew.bat') :app:assembleDebug :app:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$debugApk = Join-Path $script:ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $script:ProjectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if (-not (Test-Path -LiteralPath $debugApk)) { throw "缺少 APK：$debugApk" }
if (-not (Test-Path -LiteralPath $testApk)) { throw "缺少测试 APK：$testApk" }

Invoke-Adb wait-for-device | Out-Null
Wait-ForCondition -TimeoutSeconds 120 -FailureMessage '模拟器在 120 秒内未完成启动。' -Condition {
    ([string](Invoke-Adb shell getprop sys.boot_completed)).Trim() -eq '1'
}

$restoreAdbUnroot = $false
$stateCaptured = $false
$appDataCleared = $false
$primaryFailure = $null
$script:cleanupErrors = [System.Collections.Generic.List[string]]::new()
try {
    $initialAdbUid = ([string](Invoke-Adb shell id -u)).Trim()
    $restoreAdbUnroot = $initialAdbUid -ne '0'
    if ($restoreAdbUnroot) {
        Enable-AdbRoot
    }
    $script:volumeInputDevice = Get-VolumeInputDevice

    $originalServicesValue =
        ([string](Invoke-Adb shell settings get secure enabled_accessibility_services)).Trim()
    $originalServices = if ($originalServicesValue -eq 'null') { '' } else { $originalServicesValue }
    $originalAccessibilityEnabledValue =
        ([string](Invoke-Adb shell settings get secure accessibility_enabled)).Trim()
    $originalAccessibilityEnabled =
        if ($originalAccessibilityEnabledValue -eq 'null') { $null } else { $originalAccessibilityEnabledValue }
    $originalVolume = (Get-MediaVolume).Current
    $originalRingVolume = (Get-StreamVolume -Stream 2).Current
    $stateCaptured = $true

    Write-Host "[1/7] 安装 APK 并从空白应用数据开始"
    $baselineServiceEntries = @(
        $originalServices.Split(':') |
            Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $accessibilityComponent }
    )
    if ($baselineServiceEntries.Count -eq 0) {
        Invoke-Adb shell settings delete secure enabled_accessibility_services | Out-Null
        Invoke-Adb shell settings put secure accessibility_enabled 0 | Out-Null
    } else {
        Invoke-Adb shell settings put secure enabled_accessibility_services ($baselineServiceEntries -join ':') | Out-Null
        Invoke-Adb shell settings put secure accessibility_enabled 1 | Out-Null
    }
    Invoke-Adb install -r $debugApk | Out-Null
    Invoke-Adb install -r -t $testApk | Out-Null
    Invoke-Adb shell pm clear $packageName | Out-Null
    Invoke-Adb shell pm clear $testPackageName | Out-Null
    $appDataCleared = $true

    Write-Host "[2/7] 通过真实 Compose UI 完成显著披露并写入含 40% 跨度的离散映射"
    $instrumentLines = Invoke-Adb shell am instrument '-w' '-r' `
        '-e' class $fixtureTest `
        '-e' e2ePrepareOnly true `
        '-e' e2eLocale $AppLocale `
        $runnerComponent
    $instrumentOutput = [string]::Join([Environment]::NewLine, [string[]]$instrumentLines)
    Write-Host $instrumentOutput
    if (
        $instrumentOutput.IndexOf('OK (1 test)', [StringComparison]::Ordinal) -lt 0 -or
        $instrumentOutput.IndexOf('FAILURES!!!', [StringComparison]::Ordinal) -ge 0 -or
        $instrumentOutput.IndexOf('INSTRUMENTATION_FAILED', [StringComparison]::Ordinal) -ge 0
    ) {
        throw '显著披露/设置准备阶段 instrumentation 未通过。'
    }
    $masterSwitchDescription = Get-InstrumentationStatusValue `
        -Output $instrumentOutput `
        -Key 'e2eMasterSwitchDescription'
    $notificationTitle = Get-InstrumentationStatusValue `
        -Output $instrumentOutput `
        -Key 'e2eNotificationTitle'

    Invoke-Adb shell am start '-W' '-n' $activityComponent | Out-Null

    # 先在目标服务尚未启用时取得主开关坐标，避免 uiautomator dump 注册的
    # UiAutomation 与真实 AccessibilityService 反复断连/重绑。服务 bound 后再点击缓存坐标。
    $startButtonPoint = Get-UiTapPointByTextWithScroll `
        -Text $masterSwitchDescription `
        -AllowNonClickable

    $serviceEntries = @($originalServices.Split(':') | Where-Object { $_ -and $_ -ne 'null' })
    if ($serviceEntries -notcontains $accessibilityComponent) {
        $serviceEntries += $accessibilityComponent
    }
    Invoke-Adb shell settings put secure enabled_accessibility_services ($serviceEntries -join ':') | Out-Null
    Invoke-Adb shell settings put secure accessibility_enabled 1 | Out-Null

    Write-Host "[3/7] 启用真实 AccessibilityService，并设置确定性的媒体音量基线"
    Wait-ForCondition -FailureMessage 'AccessibilityService 未绑定。' -Condition {
        Test-AccessibilityServiceBound
    }

    $range = Get-MediaVolume
    $span = $range.Maximum - $range.Minimum
    if ($span -lt 10) { throw '媒体音量档位少于 10，无法区分 40% 映射和系统默认步长。' }
    $initialIndex = $range.Minimum + [int][Math]::Floor($span / 3)
    Set-MediaVolume -Index $initialIndex

    Write-Host "[4/7] 从可见 Activity 启动 specialUse 前台控制器"
    for ($attempt = 0; $attempt -lt 3 -and -not (Test-ControllerServiceRunning); $attempt++) {
        Invoke-UiTapPoint -Point $startButtonPoint
        Start-Sleep -Milliseconds 750
    }
    Wait-ForCondition -FailureMessage 'MappingControllerService 未以前台状态运行。' -Condition {
        Test-ControllerServiceRunning
    }
    if ([int]([string](Invoke-Adb shell getprop ro.build.version.sdk)).Trim() -ge 33) {
        Assert-NotificationAbsentFromShade -Title $notificationTitle
        Wait-ForCondition -FailureMessage '通知抽屉检查后 AccessibilityService 未重新绑定。' -Condition {
            Test-AccessibilityServiceBound
        }
    }
    Start-Sleep -Milliseconds 750

    $logicalStart = ($initialIndex - $range.Minimum) / [double]$span
    $expectedMappedUp = $range.Minimum + [int][Math]::Floor(
        ([Math]::Min(1.0, $logicalStart + 0.4) * $span) + 0.5
    )
    $logicalAfterUp = ($expectedMappedUp - $range.Minimum) / [double]$span
    $expectedMappedDown = $range.Minimum + [int][Math]::Floor(
        ([Math]::Max(0.0, $logicalAfterUp - 0.4) * $span) + 0.5
    )
    Write-Host "[5/7] 划掉最近任务卡片后注入 evdev 音量键（映射目标 $expectedMappedUp）"
    Remove-AppTaskFromRecents
    Wait-ForCondition -FailureMessage '划掉最近任务卡片后前台控制器停止运行。' -Condition {
        Test-ControllerServiceRunning
    }
    Wait-ForCondition -FailureMessage '划掉最近任务卡片后 AccessibilityService 未保持绑定。' -Condition {
        Test-AccessibilityServiceBound
    }
    Send-EmulatorVolumeKey -Direction UP
    Wait-ForCondition -FailureMessage '后台系统音量加键没有到达 40% 映射目标。' -Condition {
        (Get-MediaVolume).Current -eq $expectedMappedUp
    }
    $mappedUpIndex = (Get-MediaVolume).Current
    Send-EmulatorVolumeKey -Direction DOWN
    Wait-ForCondition -FailureMessage '系统音量减键没有沿映射曲线返回。' -Condition {
        (Get-MediaVolume).Current -eq $expectedMappedDown
    }
    $mappedDownIndex = (Get-MediaVolume).Current

    Write-Host "[6/7] 回到应用并通过主开关停止映射"
    Invoke-Adb shell am start '-W' '-n' $activityComponent | Out-Null
    Start-Sleep -Milliseconds 750
    Invoke-UiTapPoint -Point $startButtonPoint
    Wait-ForCondition -FailureMessage '应用主开关停止后前台控制器仍在运行。' -Condition {
        -not (Test-ControllerServiceRunning)
    }

    Write-Host "[7/7] 验证停止后的 fail-open：系统恢复默认音量流调整"
    Set-MediaVolume -Index $initialIndex
    $ringRange = Get-StreamVolume -Stream 2
    $ringInitial = $ringRange.Minimum + [int][Math]::Floor(
        ($ringRange.Maximum - $ringRange.Minimum) / 3
    )
    Set-StreamVolume -Stream 2 -Index $ringInitial
    Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
    $audioDumpBefore = Get-AudioServiceDump
    $systemAdjustPattern =
        'adjustSuggestedStreamVolume\([^\r\n]*from android/[^\r\n]*uid:1000'
    $appWritePattern =
        'setStreamVolume\(stream:STREAM_MUSIC[^\r\n]*from dev\.spcdts\.volumemapper\.debug'
    $systemAdjustMarkerBefore = Get-LatestAudioEventMarker `
        -Dump $audioDumpBefore `
        -EventPattern $systemAdjustPattern
    $appWriteMarkerBefore = Get-LatestAudioEventMarker `
        -Dump $audioDumpBefore `
        -EventPattern $appWritePattern
    Send-EmulatorVolumeKey -Direction UP
    Wait-ForCondition -FailureMessage '停止映射后 AudioService 没有收到系统默认音量调整。' -Condition {
        $currentDump = Get-AudioServiceDump
        $currentMarker = Get-LatestAudioEventMarker `
            -Dump $currentDump `
            -EventPattern $systemAdjustPattern
        Test-AudioEventAdvanced -Before $systemAdjustMarkerBefore -After $currentMarker
    }
    $audioDumpAfter = Get-AudioServiceDump
    $appWriteMarkerAfter = Get-LatestAudioEventMarker `
        -Dump $audioDumpAfter `
        -EventPattern $appWritePattern
    if (Test-AudioEventAdvanced -Before $appWriteMarkerBefore -After $appWriteMarkerAfter) {
        throw '主开关停止后仍出现应用媒体音量写入，fail-open 失败。'
    }
    $nativeMediaIndex = (Get-MediaVolume).Current
    $nativeRingIndex = (Get-StreamVolume -Stream 2).Current

    Write-Host "E2E PASS：后台映射 $initialIndex -> $mappedUpIndex -> $mappedDownIndex；停止后由系统 AudioService 接管，media $initialIndex -> $nativeMediaIndex，ring $ringInitial -> $nativeRingIndex。"
} catch {
    $primaryFailure = $_
} finally {
    if ($stateCaptured) {
        Invoke-CleanupStep -Description '停止 Debug 应用' -Action {
            Invoke-Adb shell am force-stop $packageName | Out-Null
        }
        if ($appDataCleared) {
            Invoke-CleanupStep -Description '清空 Debug 应用测试数据' -Action {
                Invoke-Adb shell pm clear $packageName | Out-Null
                Invoke-Adb shell pm clear $testPackageName | Out-Null
            }
        }
        if ($originalServices.Length -eq 0) {
            Invoke-CleanupStep -Description '恢复无障碍服务列表' -Action {
                Invoke-Adb shell settings delete secure enabled_accessibility_services | Out-Null
            }
        } else {
            Invoke-CleanupStep -Description '恢复无障碍服务列表' -Action {
                Invoke-Adb shell settings put secure enabled_accessibility_services $originalServices | Out-Null
            }
        }
        if ($null -eq $originalAccessibilityEnabled) {
            Invoke-CleanupStep -Description '恢复无障碍总开关' -Action {
                Invoke-Adb shell settings delete secure accessibility_enabled | Out-Null
            }
        } else {
            Invoke-CleanupStep -Description '恢复无障碍总开关' -Action {
                Invoke-Adb shell settings put secure accessibility_enabled $originalAccessibilityEnabled | Out-Null
            }
        }
        Invoke-CleanupStep -Description '恢复媒体音量' -Action {
            Set-StreamVolume -Stream 3 -Index $originalVolume
        }
        Invoke-CleanupStep -Description '恢复铃声音量' -Action {
            Set-StreamVolume -Stream 2 -Index $originalRingVolume
        }
    }
    Invoke-CleanupStep -Description '收起通知面板' -Action {
        Invoke-Adb shell cmd statusbar collapse | Out-Null
    }
    Invoke-CleanupStep -Description '返回桌面' -Action {
        Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
    }
    Invoke-CleanupStep -Description '删除设备端临时 UI dump' -Action {
        Invoke-Adb shell rm -f $remoteWindowDump | Out-Null
    }
    Invoke-CleanupStep -Description '删除宿主机临时 UI dump' -Action {
        if ([IO.File]::Exists($localWindowDump)) {
            [IO.File]::Delete($localWindowDump)
        }
    }
    if ($restoreAdbUnroot) {
        Invoke-CleanupStep -Description '恢复 adbd 普通 shell 身份' -Action {
            Disable-AdbRoot
        }
    }
}

if ($null -ne $primaryFailure) {
    if ($script:cleanupErrors.Count -gt 0) {
        Write-Warning "主流程失败，且清理存在错误：$($script:cleanupErrors -join '；')"
    }
    throw $primaryFailure
}
if ($script:cleanupErrors.Count -gt 0) {
    throw "E2E 主流程通过，但清理失败：$($script:cleanupErrors -join '；')"
}
