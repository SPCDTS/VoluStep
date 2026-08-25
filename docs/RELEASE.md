# 发布与签名

## 发布资料入口

- 发布阻断条件与逐项门禁：[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)
- Accessibility、`specialUse` FGS、Data safety 与审核说明草稿：[PLAY_CONSOLE_DECLARATIONS.md](PLAY_CONSOLE_DECLARATIONS.md)
- Google Play 中英文 listing：`fastlane/metadata/android/en-US/` 与 `fastlane/metadata/android/zh-CN/`
- 可公开托管的中英双语隐私页草稿：[privacy-policy.html](privacy-policy.html)

隐私页中的发布主体、联系邮箱和最终 URL 是明确占位符；在 [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) 的阻断项全部关闭前，不得上传生产轨道。

## 安装固定版 bundletool

发布核验固定使用 bundletool 1.18.3。首次构建前运行：

```powershell
.\scripts\install-bundletool.ps1
```

安装脚本只从 Google 官方 GitHub Release 下载 `bundletool-all-1.18.3.jar`，并强制校验 SHA-256 `A099CFA1543F55593BC2ED16A70A7C67FE54B1747BB7301F37FDFD6D91028E29`。文件存放在 `.toolchains/bundletool/`；已有文件只会被复核，不会被静默替换。

## 生成签名库

生产密钥必须由发布者本人保管，不能由 CI、版本库或聊天记录保存。确认最终发布主体后运行：

```powershell
.\scripts\create-release-keystore.ps1
```

脚本会交互式创建两个互相独立的 PKCS12 密钥库，并拒绝覆盖已有文件：

- `keystore/volustep-app-signing.p12`：跨商店应用签名密钥；用于直接分发的正式 APK，并在 Google Play App Signing 中选择“提供自己的密钥”时作为 app-signing key。
- `keystore/volustep-play-upload.p12`：只用于签署上传 Google Play 的 AAB；泄露后可按 Play 流程更换，不能拿它签其他渠道的正式 APK。

默认数字证书主题只使用产品名 `CN=VoluStep`，不会臆造发布者身份。确认法定主体后，也可以显式传入真实主题：

```powershell
.\scripts\create-release-keystore.ps1 `
    -DistinguishedName 'CN=VoluStep, O=<已确认的发布主体>, C=<国家或地区代码>'
```

脚本不会保存或输出密码。创建后应立即记录两份证书 SHA-256，并分别制作至少两份加密离线备份。不要在密码管理器之外复用或明文保存密码。应用签名密钥丢失可能导致非 Play 渠道无法继续更新。

## 构建签名 APK 与 AAB

构建脚本会交互式读取密码，用临时进程环境变量传给一次性的 Gradle daemon，并在 `finally` 中恢复环境。不要把密码写进命令行、脚本、`local.properties`、终端历史或 CI 日志。

直接分发或其他商店使用应用签名密钥：

```powershell
.\scripts\build-release.ps1 `
    -SigningProfile AppSigning `
    -ExpectedCertificateSha256 '<离线记录的 app-signing 证书 SHA-256>' `
    -PrivacyPolicyUrl 'https://<最终公开域名>/privacy'
```

Google Play 上传使用独立 upload key：

```powershell
.\scripts\build-release.ps1 `
    -SigningProfile PlayUpload `
    -ExpectedCertificateSha256 '<离线记录的 upload 证书 SHA-256>' `
    -PrivacyPolicyUrl 'https://<最终公开域名>/privacy'
```

`ExpectedCertificateSha256` 必须来自生成密钥后单独保存的离线记录；脚本会先将它与当前密钥库比对，再允许 Gradle 接触密码。`PrivacyPolicyUrl` 必须是最终公开、无用户凭据的 HTTPS URL，并通过临时进程环境变量同时写入签名 Release 的 `BuildConfig.PRIVACY_POLICY_URL` 与应用级 Manifest metadata；Debug 与未签名 Release 的两个默认值均为空字符串。

Gradle 只在 keystore、store password、alias 与 key password 四项签名环境变量全部存在时启用 Release 签名；只提供其中一部分会直接失败，不会静默生成看似成功的未签名包。正式候选包仍只能通过上述脚本生成并由校验器验收。

两种 profile 都会互斥使用当前工作区，先执行 20 条 JVM 测试、编译 10 条 instrumentation/E2E 测试、`lintRelease`、Release APK/AAB 构建和签名核验。核验包括：

- APK 的 16 KiB/4 字节 zipalign、`apksigner -Werr` 与唯一证书 SHA-256；
- APK 的包名、`versionCode` 和 `versionName`；
- APK Manifest 中的隐私政策 metadata，且必须与调用者提供的最终 HTTPS URL 精确一致；
- AAB manifest 自身的包名、`versionCode`、`versionName`，并确认与 APK 和 Gradle 一致；
- AAB Manifest 中的同名隐私政策 metadata，且必须与 APK 和调用者期望值精确一致；
- AAB 的完整 JAR 签名与唯一证书 SHA-256；
- APK、AAB 文件 SHA-256。

通过后先写入同目录唯一 staging，再以不覆盖的原子目录移动按版本与用途归档：

- `artifacts/release/1.0.0-1/app-signing/`：应用签名的 APK 与 AAB；正式直发只使用 APK。
- `artifacts/release/1.0.0-1/play-upload/`：只归档 upload key 签名的 AAB，供 Google Play 上传。

`app/build/outputs/` 是 Gradle 临时输出，不作为发布归档。不要向用户分发 upload key 签名的 APK，也不要把 app-signing profile 的 AAB 当作常规 Play 上传包。

如需独立复核既有候选包，必须同时提供预先记录的证书 SHA-256 与最终隐私政策 URL：

```powershell
.\scripts\verify-release-artifacts.ps1 `
    -ApkPath '<候选 APK>' `
    -AabPath '<候选 AAB>' `
    -ExpectedCertificateSha256 '<已记录的证书 SHA-256>' `
    -ExpectedPrivacyPolicyUrl 'https://<最终公开域名>/privacy'
```

首次启用 Google Play App Signing 时，如需保持 Play 与直接分发渠道使用同一 app-signing key，应按 Play Console 当时提供的安全导入流程提交该密钥，并把独立 upload key 注册为上传证书。不要自行通过邮件、网盘或工单发送未加密私钥。Play Console 的具体界面和导入工具可能变化，执行前应以官方当前说明为准。

## Google Play 必备声明

以下政策基线于 2026-08-25 按 Google 官方页面复核：2026-08-31 起手机/平板新应用与更新需至少 target API 36，当前项目的 target API 37 满足要求；使用 AccessibilityService 且不是残障辅助工具的应用必须在 listing 中说明用途、提交 Play Console 声明、提供正常流程内的独立显著披露并取得肯定同意；target Android 14+ 的每种 FGS 类型都需要在 Play Console 说明功能、被延迟/中断的影响并提供演示视频。

官方入口：

- [Google Play target API 要求](https://developer.android.com/google/play/requirements/target-sdk)
- [Accessibility API 政策](https://support.google.com/googleplay/android-developer/answer/16558241)
- [显著披露与同意](https://support.google.com/googleplay/android-developer/answer/11150561)
- [前台服务声明要求](https://support.google.com/googleplay/android-developer/answer/13392821)
- [前台服务权限政策](https://support.google.com/googleplay/android-developer/answer/16559646)

### Accessibility API

该应用通常不应宣称自己是专为残障人士设计的 accessibility tool，因此 API 31+ 的 XML 使用 `isAccessibilityTool=false`；基础 XML 在所有支持版本都禁用窗口内容读取。上架前需要：

- 在应用正常流程中显示独立、清晰、不可埋入隐私政策的显著披露；
- 用户主动勾选并同意后才跳转系统无障碍设置；
- Play Console 完成 Accessibility API declaration；
- 提交演示视频，展示披露、授权、实体音量键映射和停止开关；
- 说明 Android 可能投递其他实体按键事件；应用只检查其键码便立即放行，只有音量键才进一步临时处理按下/释放状态、重复次数、事件/按下时间和输入设备 ID；不读取窗口内容、文字或触摸，也不上传数据。

### specialUse 前台服务

应用不播放媒体，不能申报 `mediaPlayback`。`specialUse` 的申报说明应与 Manifest subtype 一致：用户显式启用的实体音量键映射，需要持续、即时、用户可感知的全局媒体音量控制；中断会使映射立即失效。演示材料应展示应用主开关、系统“运行中的应用”入口，以及两处停止控制器的行为。应用仍按平台要求提交启动 FGS 所需的 `Notification` 对象，但 Android 13+ 不声明通知权限，因此它不进入普通通知抽屉。

Play 审核可能不接受该用途；技术可行不等于政策必然批准。若被拒，应保留个人侧载/企业分发版本，或重新设计为仅在可见 Activity 中操作，不能伪造其他 FGS 类型。

## 备份与设备迁移策略

首版保留 `android:allowBackup="true"`，但不会迁移 `files/datastore/volume_mapper.preferences_pb`：API 30 及以下通过 `@xml/backup_rules` 从系统完整备份中排除该文件；API 31 及以上通过 `@xml/data_extraction_rules` 同时从云备份和设备到设备迁移中排除。

这是有意采用的保守策略。显著披露同意状态与映射曲线、按键参数目前保存在同一个 Preferences DataStore 中；如果只迁移设置而恢复同意状态，新设备上的首次流程可能跳过显著披露。因此首版选择不迁移整个设置文件：用户换机或从云备份恢复后需要重新阅读并同意披露，再重新配置曲线和按键参数。应用在原设备上直接升级不会受此规则影响，现有本地设置仍会保留。

## 发布前清单

以下各项是发布门槛，不代表当前提交、现有 AVD 或任何真机已经通过：

完整清单与当前阻断项统一维护在 [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)。本节保留构建和设备验证摘要。

- 首次候选确认 `versionName=1.0.0`、`versionCode=1`；后续每次上传必须递增 `versionCode`，并按发布计划更新 `versionName`；
- `testDebugUnitTest`、`compileDebugAndroidTestKotlin`、`lintRelease`、`assembleRelease`、`bundleRelease` 全部通过；
- API 36 与 37 模拟器分别实际运行 `connectedDebugAndroidTest`，确认 10/10 通过；
- API 36 与 37 模拟器通过；只要仍声明 `minSdk=28`，API 28 至少完成安装、启动和关键链路基础检查；
- Xiaomi HyperOS、Pixel、Samsung，及至少一个 OPPO/vivo/Honor 系真机通过；
- 蓝牙 A2DP 绝对音量开/关、LE Audio（若有）、耳机自身按键已记录；
- 截图组合键、通话、闹钟、相机和锁屏 fail-open 行为已验证；
- Data safety、隐私政策、Accessibility 和 FGS 声明与应用实际行为一致；
- 检查最终 APK 的 `fullBackupContent` 与 `dataExtractionRules` 引用，并确认两套规则都排除了 `files/datastore/volume_mapper.preferences_pb`；
- 用 `apksigner` 验证证书，并把 AAB 上传到内部测试轨道而非直接生产发布。

如果使用的是 2023-11-13 之后创建的个人开发者账号，还必须先完成至少 12 名测试者连续加入 14 天的封闭测试，再申请生产访问权限；组织账号或较早创建的个人账号应以 Play Console 实际显示的门禁为准。官方说明见 [新个人开发者账号测试要求](https://support.google.com/googleplay/android-developer/answer/14151465)。
