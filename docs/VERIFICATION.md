# 本机验证记录

本记录描述 2026-08-21 在 Windows 开发机上对代码提交 `d118c3f523a79f18e58cac09683271b6092736d4` 的实际验证。后续文档提交不修改应用代码。

## 自动验证结果

| 项目 | 环境 | 结果 |
|---|---|---|
| JVM 单元测试 | Studio JBR 25.0.2、Gradle 9.5.0 | 35/35 通过 |
| Debug lint | compileSdk/targetSdk 37 | 0 个 error；3 个依赖版本可用性提示 |
| Android instrumentation | API 37 Google APIs x86_64 | 2/2 通过 |
| Android instrumentation | API 28 Google APIs x86_64 | 2/2 通过 |
| Debug APK | API 37 工具链 | 构建通过 |
| Release APK/AAB | R8 开启、无生产签名 | 构建通过 |

完整验证命令：

```powershell
. .\scripts\android-env.ps1
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:bundleRelease
```

API 28 的 `connectedDebugAndroidTest` 在切换模拟器后单独再次执行。两个设备侧测试分别覆盖 Compose 主界面/曲线编辑控件，以及 coordinator 通过真实 `AudioManager` 改变 `STREAM_MUSIC` index 后的清理。它们不模拟系统向 AccessibilityService 分派实体按键。

## 模拟器基线

| AVD | 系统指纹 | 安全补丁 |
|---|---|---|
| `VolumeMapper_API_28` | `google/sdk_gphone_x86_64/generic_x86_64:9/PSR1.180720.122/6736742:userdebug/dev-keys` | 2019-08-05 |
| `VolumeMapper_API_37` | `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys` | 2026-05-05 |

API 28 smoke 流程安装了 Debug APK，并通过 `dumpsys accessibility` 确认“音量键映射服务”已绑定且具有按键过滤能力。API 37 上人工检查了控制页和曲线页：连续曲线、设备量化阶梯、控制点、Slider 与预设均可见且没有明显裁切。

## 签名验证

使用一次性测试 keystore 执行了 Release 单测、lint、APK 和 AAB 构建。`apksigner` 验证测试签名 APK 通过，`jarsigner` 验证测试签名 AAB 通过；自签名证书的信任链和时间戳警告符合预期。测试 keystore 随后已删除。

仓库当前 Release 产物保持未签名状态。生产 keystore 和 Play Console 身份必须由发布者自行保管并通过 `scripts/build-release.ps1` 注入，不能使用本次测试身份。

## 尚未覆盖的硬件验证

本机没有小米/HyperOS 真机和真实蓝牙耳机，因此以下项目不能记为通过：

- HyperOS 的无障碍、媒体音量、通知、自启动和省电策略；
- A2DP 绝对音量、LE Audio VCS、耳机固件合档和安全音量提示；
- 熄屏、锁屏、来电、闹钟、相机、截图组合键以及耳机自身按键；
- API 31/32 和 API 33–36 的中间版本分支；
- Play Console 的 Accessibility API 与 `specialUse` FGS 声明审核。

真机验收和诊断采集步骤见 [TESTING.md](TESTING.md) 与 [OEM_COMPATIBILITY.md](OEM_COMPATIBILITY.md)。
