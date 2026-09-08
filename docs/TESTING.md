# 测试说明

环境配置见 [开发环境](TOOLCHAIN.md)。测试结果记录在对应 PR 或发布记录中，包含提交或工作区状态、日期、设备/API/ROM、完整命令和 pass/fail/skip 数量。编译测试 APK 不代表设备测试通过，被跳过的测试也不计为通过。

## 本机构建与 JVM 测试

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug
./gradlew :app:lintRelease :app:assembleRelease
```

Windows 使用 `gradlew.bat`；采用仓库辅助环境时，先执行 `. .\scripts\android-env.ps1`。

| 测试源码 | 主要覆盖 |
|---|---|
| `StepVolumeMapTest` | 整数控制点约束、插值与投影、拖动、路由绑定和重建基准 |
| `VolumeMappingReducerTest` | 严格相邻步进、300 ms 长按边界、时间累积、松手、取消和外部音量同步 |
| `SettingsSerializationTest` | 设置编解码、旧格式迁移、损坏字段降级和保存队列 |
| `MappingCoordinatorStateTest`、`AccessibilityKeyFreshnessTest` | 状态一致性、命令优先级、固定音量回读竞争和迟到事件处理 |

源码位于 [`app/src/test`](../app/src/test)。[Android CI](../.github/workflows/android.yml) 执行上述构建检查，并限制 JVM 与 Android 测试合计不超过 30 个 `@Test` 入口；CI 不运行设备测试。

## Android 设备测试

连接专用测试设备或模拟器，在 PowerShell 中选择目标后运行：

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest
```

[`app/src/androidTest`](../app/src/androidTest) 包含以下测试：

| 测试类 | 主要覆盖与边界 |
|---|---|
| `MainActivityTest` | 单页界面、隐私菜单、点/线段选择、增删、按键次数、双轴吸附及无障碍操作 |
| `FixedVolumePresetTest` | 连续添加删除、关闭映射时的真实音量写入、按钮与曲线状态同步 |
| `VolumeKeyAudioIntegrationTest` | 直接向 coordinator 传入手势，验证真实媒体音量；不验证系统按键分派 |
| `RealSystemVolumeE2eTest` | 披露流程、真实无障碍绑定、运行窗口、前台服务和停止流程；实体键分派由下述宿主脚本验证 |

普通设备测试会保留 Debug 应用数据，已接受披露的设备走已同意路径。需要从空白数据验证首次披露时，使用宿主 E2E。

## 模拟器实体键链路

```powershell
.\scripts\emulator-e2e-test.ps1 -Serial emulator-5554 -AppLocale zh-CN
```

脚本默认先构建并安装应用和测试 APK；已有最新产物时可加 `-SkipBuild`。它验证披露、无障碍服务、前台控制器、通知行为、后台音量加减，以及停止后由系统恢复接管。

**仅在可丢弃的 `emulator-*` 上运行。** 脚本会在开始和结束时清空应用与测试包数据，测试前设置无法恢复；无障碍配置、媒体/铃声音量、通知面板和临时 adbd root 状态会在 `finally` 中恢复，清理失败会使测试失败。

脚本从 Linux evdev 注入按键，以经过 Accessibility input filter。`adb shell input keyevent` 和 UiAutomation 注入不能替代这一阶段，因为它们绕过该过滤链路。`emulator-smoke-test.ps1` 仅用于快速安装和启动，不替代完整 E2E。

仓库提供 API 28、36、37 的 AVD 定义。API 28 覆盖最低版本和启发式路由，36 覆盖公开媒体路由及现代前台服务行为，37 覆盖目标平台。涉及 API 31/32、OEM 或音频硬件的改动需要补充对应设备验证。

## 真机与人工检查

真机通过应用界面完成披露、手动启用无障碍并打开主开关，不运行会覆盖授权配置的模拟器脚本。测试人员实际按手机侧键，至少检查：

- 扬声器及可用的 A2DP、LE Audio、有线/USB 路由；记录系统范围，并区分系统回读与实际可听变化。
- 短按、长按、快速反向、边界音量，以及耳机自身调节后再按手机侧键。
- 应用前台、播放器前台、锁屏/熄屏、蓝牙重连、系统重启，以及关闭控制器后的默认按键行为。
- 通话、相机或截图组合键，以及其他无障碍服务共存时的行为。
- 曲线点/线段选择、插入与删除、设置重载，以及浅色、深色和窄屏下的文字、坐标和控制点显示。

HyperOS 的既有测试中，UiAutomator 层级采集曾触发无障碍服务重新绑定。检查真实按键连续性时使用截图、只读 `dumpsys` 和 logcat，避免同时抓取 UI 层级。后台问题需记录播放场景和回调延迟；调整电池策略时，每次只改变一个条件再对照复测。

```powershell
.\scripts\capture-device-diagnostics.ps1 -Serial SERIAL
```

诊断输出保存在被 Git 忽略的 `artifacts/`。分享前检查设备标识和其他应用信息；兼容性背景见 [设备与耳机兼容性](OEM_COMPATIBILITY.md)。
