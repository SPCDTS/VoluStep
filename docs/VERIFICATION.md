# 本机验证记录

## 2026-08-22 完整模拟器 E2E

本记录随本次 E2E 提交保存。测试输入为父提交 `29d9b27` 加本次测试代码、宿主脚本和文档改动。终审后又把 fail-open 改为最新事件时间戳比较、让任何清理失败都阻断脚本，并把 UI dump 移到目标无障碍服务启用前；最终脚本在 API 36/37 再次通过。执行机为 Windows，使用项目隔离的 Studio JBR 25.0.2、Gradle 9.5.0、Android Platform Tools 37.0.1 和 Emulator 37.1.11。

当前 `adb devices -l` 只列出 `emulator-5554`，没有小米真机或蓝牙耳机。因此本节所说“完整 E2E”严格指仓库可自动执行的 AOSP 模拟器链路，不包含 OEM 或真实蓝牙硬件验收。

### 总结果

| 项目 | 结果 |
|---|---|
| 干净构建 | `clean` 后 150/150 个 Gradle task 成功 |
| JVM 单元测试 | 35/35 通过，0 failure、0 error、0 skipped |
| Debug lint | 0 blocking；3 条工具/依赖有新版本提示 |
| Release lint | 0 blocking；3 条工具/依赖有新版本提示 |
| Android instrumentation / API 28 | 3/3 通过，0 skipped、0 failed |
| Android instrumentation / API 36 | 3/3 通过，0 skipped、0 failed |
| Android instrumentation / API 37 | 3/3 通过，0 skipped、0 failed |
| 真实系统按键宿主 E2E / API 28、36、37 | 三档均通过：`5 → 11 → 5`、通知停止、系统 fail-open |
| 签名 R8 Release 安装烟测 | API 28、36、37 均安装成功、MainActivity 冷启动成功、披露入口可达、无 `DEBUGGABLE` |
| Release 黑盒门禁 | APK 对齐/签名、AAB 签名、最终 Manifest 与无障碍 XML 全部通过 |

最终干净构建命令为：

```powershell
. .\scripts\android-env.ps1
.\gradlew.bat clean `
  :app:testDebugUnitTest `
  :app:lintDebug `
  :app:lintRelease `
  :app:assembleDebug `
  :app:assembleDebugAndroidTest `
  :app:assembleRelease `
  :app:bundleRelease `
  --rerun-tasks --stacktrace --no-problems-report
```

Release 构建使用一次性本地 PKCS12 测试证书；完整校验后私钥已删除。每台 AVD 依次执行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-problems-report --stacktrace
.\scripts\emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild
```

### 模拟器矩阵

| AVD | 系统指纹 | instrumentation | evdev E2E | 签名 Release 冷启动 |
|---|---|---:|---|---|
| `VolumeMapper_API_28` | `google/sdk_gphone_x86_64/generic_x86_64:9/PSR1.180720.122/6736742:userdebug/dev-keys` | 3/3 | `5 → 11 → 5` | 1205 ms；披露入口滚动后可见 |
| `VolumeMapper_API_36` | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` | 3/3 | `5 → 11 → 5` | 1467 ms |
| `VolumeMapper_API_37` | `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys` | 3/3 | `5 → 11 → 5` | 758 ms |

API 28 首次 Release UI 断言使用了旧系统不可靠的 `uiautomator dump /dev/tty`，且没有滚动长页面，因此虽然 APK 安装和 Activity 启动成功，自动断言没有看到屏幕外的“查看并同意”。文件式 UI dump 和截图确认页面已正常渲染；滚动后复验通过。宿主 E2E 随后增加有界滚动定位，并在 API 28、36、37 全部重新执行通过。

终审的通用可靠性修订不改变映射算法或应用 APK；修订后的宿主脚本又在 API 36/37 完整通过。按当前产品方向，API 28 保留上述已通过的基础兼容记录，但不再作为持续开发的阻断门禁，也不为它增加专用产品逻辑。

### 系统按键链路的证明范围

宿主脚本先执行 `pm clear`，其定向 Instrumentation 会真实完成 Compose 显著披露、绑定 `VolumeKeyAccessibilityService`、从可见 Activity 启动 `specialUse` FGS、检查前台通知，并从通知 action 停止控制器。普通 `connectedDebugAndroidTest` 若检测到已持久化的同意状态，则验证已同意路径而不重复显示披露。测试同时为 UIAutomator `Configurator` 与 Instrumentation `UiAutomation` 设置 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，避免测试框架压制目标服务。

`adb shell input keyevent`、`UiDevice.pressKeyCode()` 和 `UiAutomation.injectInputEvent()` 都会绕过 Accessibility input filter，不能证明 `AccessibilityService.onKeyEvent()` 收到了系统按键。宿主脚本因此临时执行 `adb root`，动态找到声明 `KEY_VOLUMEUP` 的 `/dev/input/event*`，再用 `sendevent` 产生 DOWN/SYN/UP/SYN。三档系统都观察到：

1. Activity 退到后台后，线性曲线和 40% 短按步长把媒体 index 精确映射为 `5 → 11 → 5`；
2. `dumpsys audio` 中写入调用方是 `dev.spcdts.volumemapper.debug`；
3. SystemUI 常驻通知的“停止映射”立即终止前台控制器；
4. 停止后的下一次 evdev 按键新增系统 `AudioService.adjustSuggestedStreamVolume` 记录，且不再新增应用 `setStreamVolume`，证明按键 fail-open。此时 media/ring index 可能因当前非活动流而保持不变，所以断言使用系统调用记录而不是错误地要求某个流必然变化。

### Release 产物与黑盒检查

| 产物 | 字节数 | SHA-256 |
|---|---:|---|
| `app-release.apk` | 2,310,437 | `4F7CDC6D396F60691B66B6F836297255F60D76DF545CE6D47BA40E520A737BDD` |
| `app-release.apk.idsig` | 26,646 | `0A76A9C263DA866F4E529CF211E2303944C3E08E64E28413079C1B5DAF6512E2` |
| `app-release.aab` | 3,124,368 | `C18649919FCF7329D137856C769A88BCAD619E74E5A94BEED4B22683566A776C` |

- `zipalign -c -P 16 -v 4` 与 `apksigner verify -Werr --verbose --print-certs` 均 exit 0；APK 使用 V3 签名，覆盖项目的 minSdk 28；独立 `.idsig` 未由普通 APK verify 命令计入 V4；
- `jarsigner -verify -verbose -certs` 对 AAB exit 0，并输出 `jar verified.`；
- 一次性证书 SHA-256 为 `6676078D11A1F5FB51842D1926174AAB8D98B5497BA6FC03F6362916E710AE14`，APK/AAB 相同；该证书不是生产身份，私钥已删除；
- 最终 APK 为 `dev.spcdts.volumemapper`、version `1 (0.1.0)`、minSdk 28、targetSdk 37，没有 `instrumentation` 和 `debuggable`；
- `MappingControllerService` 为 `exported=false`；无障碍服务受 `BIND_ACCESSIBILITY_SERVICE` 保护；FGS 类型和 subtype 均为 `specialUse`；
- 压缩后的两份无障碍 XML 均为 `canRequestFilterKeyEvents=true`、`canRetrieveWindowContent=false`，API 31+ 版本另有 `isAccessibilityTool=false`。

### 清理与恢复

最终停留在 API 37 AVD。只读复核结果：adbd 为 `uid=2000(shell)`，`accessibility_enabled=0`，`enabled_accessibility_services=null`，目标 Debug FGS、进程和服务均不存在。宿主脚本还在每次 `finally` 中恢复进入测试前的媒体/铃声音量、无障碍服务列表和 adbd 身份，并收起通知面板；任何清理失败都会使脚本以失败结束。Debug 应用数据按脚本设计会在测试开始和结束时清空，不能恢复测试前内容。一次性测试 keystore 已从 `artifacts/release-e2e/` 删除。

### 尚未通过的发布门槛

- 小米/HyperOS 真机、A2DP 绝对音量、LE Audio VCS、耳机自身按键和安全音量提示尚未执行；
- API 31/32、API 33–35 以及 Pixel、Samsung、OPPO/vivo/Honor 真机尚未执行；
- 来电、闹钟、相机、截图组合键、锁屏、省电和 ROM 杀后台仍需真机矩阵；
- `android:allowBackup="true"` 且没有 backup/data-extraction rules，DataStore 的 `disclosureAccepted` 可能通过系统备份或设备迁移恢复并跳过新的显著披露。这是正式发布前必须解决的风险；
- Accessibility API、`specialUse` FGS、Data safety 与隐私政策仍需 Play Console 审核，自动测试不能代替政策批准。

## 2026-08-21 基线记录

提交 `d118c3f523a79f18e58cac09683271b6092736d4` 当时完成了 35/35 JVM 单测、Debug lint 0 error、API 28 与 API 37 各 2/2 instrumentation，以及未签名 Release APK/AAB 构建。该轮设备测试没有模拟系统向 AccessibilityService 分派实体按键；本次 2026-08-22 E2E 已补上真实服务、FGS、通知和 evdev 按键链路。
