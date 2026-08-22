# 发布与签名

## 生成签名库

生产签名库必须由发布者保管，不能由 CI 或版本库保存。首次发布前运行：

```powershell
.\scripts\create-release-keystore.ps1
```

脚本会在已被 `.gitignore` 排除的 `keystore/` 中创建 PKCS12 签名库，并拒绝覆盖已有文件。请立即做至少两份加密离线备份。签名库丢失会导致无法更新已经发布的应用。

## 构建签名 APK 与 AAB

只在当前 PowerShell 进程设置变量，不要把密码写进脚本、`local.properties`、终端历史或 CI 日志：

```powershell
$env:VOLUME_MAPPER_KEYSTORE = (Resolve-Path .\keystore\volume-mapper-release.jks).Path
$storeSecret = Read-Host 'Store password' -AsSecureString
$storeCredential = [PSCredential]::new('unused', $storeSecret)
$env:VOLUME_MAPPER_STORE_PASSWORD = $storeCredential.GetNetworkCredential().Password
$env:VOLUME_MAPPER_KEY_ALIAS = 'volume-mapper-release'
$keySecret = Read-Host 'Key password' -AsSecureString
$keyCredential = [PSCredential]::new('unused', $keySecret)
$env:VOLUME_MAPPER_KEY_PASSWORD = $keyCredential.GetNetworkCredential().Password
.\scripts\build-release.ps1
```

构建结束后清理当前进程变量：

```powershell
Remove-Item Env:VOLUME_MAPPER_KEYSTORE
Remove-Item Env:VOLUME_MAPPER_STORE_PASSWORD
Remove-Item Env:VOLUME_MAPPER_KEY_ALIAS
Remove-Item Env:VOLUME_MAPPER_KEY_PASSWORD
$storeCredential = $null
$storeSecret = $null
$keyCredential = $null
$keySecret = $null
```

发布产物：

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

用 SDK Build Tools 验证 APK：

```powershell
. .\scripts\android-env.ps1
$apk = '.\app\build\outputs\apk\release\app-release.apk'
$aab = '.\app\build\outputs\bundle\release\app-release.aab'
& "$env:ANDROID_HOME\build-tools\37.0.0\zipalign.exe" -c -P 16 -v 4 $apk
& "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" verify -Werr --verbose --print-certs $apk
& "$env:JAVA_HOME\bin\jarsigner.exe" -verify -verbose -certs $aab
```

必须把 `apksigner` 输出的证书 SHA-256 与既有生产证书或 Play upload key 对照，不能只检查命令 exit 0。还应使用 `aapt2 dump badging` 与 `aapt2 dump xmltree` 检查最终 APK，而不是只审查源码 Manifest。

## Google Play 必备声明

### Accessibility API

该应用通常不应宣称自己是专为残障人士设计的 accessibility tool，因此 API 31+ 的 XML 使用 `isAccessibilityTool=false`；基础 XML 在所有支持版本都禁用窗口内容读取。上架前需要：

- 在应用正常流程中显示独立、清晰、不可埋入隐私政策的显著披露；
- 用户主动勾选并同意后才跳转系统无障碍设置；
- Play Console 完成 Accessibility API declaration；
- 提交演示视频，展示披露、授权、实体音量键映射和停止开关；
- 说明只读取音量键 KeyEvent，不读取窗口内容、文字或触摸，不上传数据。

### specialUse 前台服务

应用不播放媒体，不能申报 `mediaPlayback`。`specialUse` 的申报说明应与 Manifest subtype 一致：用户显式启用的实体音量键映射，需要持续、即时、用户可感知的全局媒体音量控制；中断会使映射立即失效。还要展示持续通知和停止 action。

Play 审核可能不接受该用途；技术可行不等于政策必然批准。若被拒，应保留个人侧载/企业分发版本，或重新设计为仅在可见 Activity 中操作，不能伪造其他 FGS 类型。

## 发布前清单

以下各项是发布门槛，不代表当前提交、现有 AVD 或任何真机已经通过：

- 更新 `versionCode` / `versionName`；
- `testDebugUnitTest`、`lintRelease`、`assembleRelease`、`bundleRelease` 全部通过；
- API 36 与 37 模拟器通过；只要仍声明 `minSdk=28`，API 28 至少完成安装、启动和关键链路基础检查；
- Xiaomi HyperOS、Pixel、Samsung，及至少一个 OPPO/vivo/Honor 系真机通过；
- 蓝牙 A2DP 绝对音量开/关、LE Audio（若有）、耳机自身按键已记录；
- 截图组合键、通话、闹钟、相机和锁屏 fail-open 行为已验证；
- Data safety、隐私政策、Accessibility 和 FGS 声明与应用实际行为一致；
- 为 DataStore 配置 API 30 以下 `fullBackupContent` 与 API 31+ `dataExtractionRules`，确保显著披露同意状态不会经备份或设备迁移跳过；
- 用 `apksigner` 验证证书，并把 AAB 上传到内部测试轨道而非直接生产发布。
