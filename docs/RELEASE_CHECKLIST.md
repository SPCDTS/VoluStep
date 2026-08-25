# VoluStep 发布门禁清单

本清单是发布阻断条件的唯一汇总入口。勾选只代表已有材料，不代表 Google Play、设备厂商或其他商店必然批准。详细的签名与构建步骤见 [RELEASE.md](RELEASE.md)，验证证据见 [VERIFICATION.md](VERIFICATION.md)。

Play Console 的 Accessibility、`specialUse` FGS、Data safety 和审核说明草稿集中在 [PLAY_CONSOLE_DECLARATIONS.md](PLAY_CONSOLE_DECLARATIONS.md)。

## 当前状态

**阻断发布。** 以下公开信息和审核材料尚未由发布者提供，不能虚构，也不能带占位符提交：

- [ ] 发布主体法定名称：替换 `[[PUBLISHER_LEGAL_NAME]]`
- [ ] 有效隐私联系邮箱：替换 `[[PRIVACY_CONTACT_EMAIL]]`
- [ ] 隐私政策最终 HTTPS 地址：替换 `[[FINAL_PRIVACY_POLICY_URL]]`
- [ ] Accessibility 声明演示视频公开地址：替换 `[[ACCESSIBILITY_DEMO_VIDEO_URL]]`
- [ ] `specialUse` FGS 演示视频公开地址：替换 `[[FGS_DEMO_VIDEO_URL]]`
- [ ] 隐私信息填写并复核后，删除隐私页顶部的“发布前草稿 / Pre-release draft”提示

发布主体、联系邮箱与隐私 URL 占位符位于 [privacy-policy.html](privacy-policy.html)，演示视频等申报占位符位于 [PLAY_CONSOLE_DECLARATIONS.md](PLAY_CONSOLE_DECLARATIONS.md)。全部填写后必须执行：

```powershell
rg -n "\[\[[A-Z0-9_]+\]\]" docs fastlane
rg -n "发布前草稿|Pre-release draft" docs/privacy-policy.html
```

命令应无输出。随后把隐私页部署到无需登录、无地域限制、可长期访问的 HTTPS 地址，并用未登录浏览器和手机各打开一次。不得把本地路径、GitHub 编辑页或临时预览地址填写到 Play Console。

## 身份、包名与签名

- [ ] 确认最终应用名为 `VoluStep`，中英文保持一致。
- [ ] 确认最终 `applicationId`。当前为 `dev.spcdts.volumemapper`；首次发布后不能通过改包名延续原应用更新链。
- [ ] 确认发布主体、Play Console 开发者资料、隐私政策主体和其他商店备案主体一致。
- [ ] 创建生产 app-signing key 与独立 Play upload key；不提交密钥或密码。
- [ ] 对生产密钥做至少两份加密离线备份，并记录证书 SHA-256。
- [ ] 把两份证书 SHA-256 保存到独立于工作区和密钥文件的发行记录；正式构建必须通过 `-ExpectedCertificateSha256` 与该记录比对。
- [ ] 确认所有分发渠道的签名和升级策略，避免同包名安装包无法互相覆盖升级。
- [ ] 首次公开候选包使用 `versionName=1.0.0`、`versionCode=1`，并确认构建产物与发行说明一致；后续每次上传都必须递增 `versionCode`。

## 商店资料

- [x] Google Play 英文 listing：`fastlane/metadata/android/en-US/`
- [x] Google Play 简体中文 listing：`fastlane/metadata/android/zh-CN/`
- [x] `versionName=1.0.0`、`versionCode=1` 的中英文 changelog 草稿（fastlane 文件名为 `changelogs/1.txt`）
- [x] 中英双语静态隐私页草稿：`docs/privacy-policy.html`
- [ ] 人工复核标题、短描述、完整描述和 changelog；不得承诺所有手机、锁屏或耳机按键均可用。
- [x] 已准备应用图标、功能图及中英文 Debug 签名包手机截图草稿：`distribution/store-assets/`。
- [ ] 生产 AAB 进入内部测试轨道后复核或重截手机截图；正式截图必须与本次候选包一致。
- [ ] 将最终隐私政策 URL 填入 Play Console，并确认应用内也提供可访问入口。
- [ ] 使用同一个最终 HTTPS 地址执行签名构建的 `-PrivacyPolicyUrl`；确认候选包内“隐私 → 完整政策”能在未登录浏览器中打开该地址。
- [ ] 填写支持邮箱；如提供网站或电话，确认可公开访问且由发布者控制。
- [ ] 确认内容分级、目标受众、广告声明和应用访问说明。

## Data safety 与隐私

- [ ] 对最终 AAB 再次确认无 `INTERNET` 权限、广告 SDK、分析 SDK、账号系统或其他数据传输代码。
- [ ] 仅在上述事实仍成立时，将 Data safety 申报为“不收集、不共享用户数据”。
- [ ] 确认隐私政策准确说明本地设置、删除方式、无障碍 KeyEvent、音频路由信息和诊断信息。
- [ ] 确认清除应用数据或卸载可以删除应用私有配置；应用没有服务器账号或服务器端数据删除流程。
- [ ] 确认备份规则仍排除 `files/datastore/volume_mapper.preferences_pb`，避免在新设备恢复显著披露同意状态。
- [ ] 对依赖与最终合并 Manifest 做一次发布前 Play Policy Insights 审计，并归档报告。

## Accessibility API 声明

- [ ] 保持 `isAccessibilityTool=false`，不把 VoluStep 描述为面向残障人士的辅助工具。
- [ ] 正常流程中的显著披露独立、清晰，且先于跳转系统无障碍设置。
- [ ] 用户必须主动勾选同意；拒绝后不启用服务。
- [ ] 准确声明系统可能投递其他实体按键事件；应用检查键码后立即放行非音量键，只对手机实体音量加减键进一步处理并应用用户配置的媒体音量映射。
- [ ] 明确说明不读取窗口内容、屏幕文字、触摸、密码、账号或其他应用内容，也不上传按键与音量数据。
- [ ] 准备未剪掉关键步骤的审核视频：首次披露 → 不勾选时同意禁用 → 取消且不授权/不启动 → 再次触发披露 → 主动同意 → 系统授权 → 开启总开关 → 其他应用中的手机实体音量键映射 → 停止并恢复系统行为。
- [ ] Play Console 的 Accessibility API declaration、商店文案、隐私政策、应用内披露和视频用语一致。

## 前台服务声明

- [ ] FGS 类型保持 `specialUse`，不得误报为 `mediaPlayback`。
- [ ] subtype 与实际用途一致：用户主动启用的实体音量键映射和即时全局媒体音量控制。
- [ ] 审核视频展示用户如何启动、查看运行状态并从应用及系统入口停止控制器。
- [ ] 确认控制器不会在开机、无障碍回调或应用更新后自行启动。
- [ ] 准备审核不接受 `specialUse` 时的降级或替代分发方案，不通过伪造 FGS 类型规避审核。

## 功能与兼容性

- [ ] 说明应用控制 Android 媒体音量整数 index，不等同于分贝、耳机内部增益或真实声压级。
- [ ] 说明耳机自身按键或触控通常由蓝牙栈/固件处理，可能绕过 VoluStep。
- [ ] 说明蓝牙绝对音量、LE Audio、固定音量设备、系统安全音量和 OEM 后台策略可能改变结果。
- [ ] 确认关闭总开关、无障碍断连、固定音量、错误阈值和不支持场景均 fail-open，不吞掉系统按键。
- [ ] 至少完成 Pixel、Samsung、小米和一个 OPPO/vivo/Honor 系设备的候选包验证；未覆盖的组合不得写成“兼容”。
- [ ] 记录蓝牙 A2DP 绝对音量开/关、可用的 LE Audio 组合，以及至少一组耳机自身按键结果。

## 候选包验证与投放

- [ ] 运行 `scripts/install-bundletool.ps1`，确认固定版 bundletool 1.18.3 的 SHA-256 校验通过。
- [ ] `testDebugUnitTest`、`compileDebugAndroidTestKotlin`、`lintRelease`、`assembleRelease`、`bundleRelease` 全部通过；编译设备测试不等于已在设备上运行。
- [ ] API 36 与 37 模拟器分别实际运行 `connectedDebugAndroidTest`，确认 10/10 通过。
- [ ] Release APK/AAB 黑盒检查通过：包名、版本、权限、组件导出、无障碍 XML、备份规则与 FGS 类型均符合预期。
- [ ] `verify-release-artifacts.ps1` 验证通过：APK 对齐与唯一签名者、APK/AAB 包名和版本、AAB 完整签名与唯一签名者均一致，证书 SHA-256 与独立发行记录一致。
- [ ] 从候选 AAB 生成的 Play 分发 APK 在目标设备完成安装、升级、首次披露、授权、映射、停止和卸载验证。
- [ ] 先上传内部测试轨道，确认 Play 预发布报告与自动设备结果。
- [ ] 再进入封闭测试，收集不同 ROM、媒体应用、蓝牙路由和锁屏场景的结果。
- [ ] 如果是 2023-11-13 之后创建的个人开发者账号，至少 12 名测试者连续加入封闭测试 14 天，并在 Dashboard 申请生产访问权限；其他账号按 Play Console 当前门禁执行。
- [ ] 所有政策声明获批且阻断缺陷归零后，才创建生产发布；首次生产建议分阶段放量并保留回滚决策记录。
