# 本机验证记录

## 2026-08-24 自由控制点与正式单页收口

本轮把“从最小到最大需要的按键次数 `K`”与“搭建折线的控制点总数 `P`（含两端）”完全解耦，并允许中间控制点保存自由 x。只有 `K+1` 个实际按键位置按 `n/K` 均匀分布；控制点在 x 轴吸附到邻近按键位置、在 y 轴吸附到整数 audio index。保存格式升级为 `v3|basisSpan|K|x,offset;…`，同时保留 `v1` / `v2` 和 legacy shadow 迁移。

验证结果：

- `scripts/build.ps1 -Release` 与补充的 `:app:lintRelease`：通过；22/22 个 JVM 场景组无 failure、error 或 skip，Debug / Release lint 均无阻断项，R8/资源压缩后的未签名 Release APK 与 AAB 均成功生成；原细粒度断言保留在场景组内；
- 最终 Release lint 为 0 error、3 个工具/依赖版本更新 warning、1 个 Compose 状态类型优化 hint；Debug APK、未签名 Release APK、Release AAB 的 SHA-256 依次为 `AEC89128D0AA16B5BC02746ECCD823F6A1024477C019C5C81A6E87EE46FB2B14`、`01922E018D74A203C0CF3FC589D8B5B757C8C6E8802737E113ED9A5CFE3CC8D9`、`4DCF3EF9383486EDADBDA1A9F7938D2D46104FEBC1C07298FB1E615BE88A334C`；
- `:app:connectedDebugAndroidTest`：API 37 模拟器 8/8 通过，0 skip、0 failure；其中单页 UI 6 项，真实 `AudioManager` 集成 1 项，显著披露、真实 AccessibilityService、`specialUse` FGS 与通知停止 1 项；与 JVM 合计 30 个测试入口；
- `scripts/emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild`：通过，真实 AccessibilityService、`specialUse` FGS 与 evdev 链路完成后台映射 `5 → 11 → 5`，通知停止后由系统接管；
- 视觉与交互检查使用 Android CLI 和 1080×2400 的 `VolumeMapper_API_37`：全新数据默认 `basis=30、K=18、P=5`，0…15 路由按实际 index 显示；浅色与深色模式均确认正式版只有一个主界面，当前音量为绿色水平线、交点和“当前 n”标签，y 轴音量图标不与刻度重叠，`P` 位于图上、`K` 位于 x 轴下、长按间隔与折叠设备区均无裁切。截图保存在被 Git 忽略的 `app/build/verification/final-*.png`。

本轮没有连接 Xiaomi 真机或蓝牙耳机，因此这里只确认模型、持久化、Android 公开音量链路和模拟器实体按键分派；耳机端可听档位仍需在目标设备上复测。

## 2026-08-24 简约 UI 中间阶段（历史）

本节记录正式单页收口前的中间工作树；最终界面、测试数量和交互语义以上一节为准。

本轮把高频操作留在默认层，把精确输入、按键响应、设备底层信息和后台排障建议改为按需展开；同时统一为单一蓝色强调色、中性 surface，并支持系统明暗模式。显著披露、fail-open、路由降级和固定音量提示没有因简化界面而删除。

验证设备为 `VolumeMapper_API_37`，序列号 `emulator-5554`，分辨率 `1080×2400`，Android 17 / API 37。执行结果：

- `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:bundleRelease`：通过；最终与设备测试合并执行时共完成 149 个 Gradle task；
- `:app:connectedDebugAndroidTest`：3/3 通过，0 skip、0 failure；覆盖主导航与折叠设置、真实 `AudioManager` 集成，以及显著披露、无障碍连接、前台控制器和通知停止流程；
- `scripts/emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild`：通过。宿主从空白应用数据开始完成披露，后台 evdev 音量键按离散映射完成 `5 → 11 → 5`，再从 SystemUI 常驻通知停止控制器并验证 fail-open；脚本在 `finally` 中恢复模拟器无障碍、音量与 adbd 状态；
- 使用 Android CLI 检查控制、曲线默认层、精确编辑、诊断折叠区，并分别在系统浅色与深色模式目视复核；浅色状态栏使用深色系统图标，深色模式使用浅色图标。强制显示数字软键盘时，聚焦的 `K` 输入仍保持可见。没有发现 1080×2400 窄屏上的横向溢出、系统栏遮挡或不可达控件。截图保存在被 Git 忽略的 `artifacts/minimal-*.png`。

本轮只有 API 37 模拟器连接，未把这次纯 UI 改动冒充为新的 Xiaomi / 蓝牙耳机真机验证；下方既有真机音频链路结论仍对应当时安装包与记录条件。

## 2026-08-24 离散按键曲线重构

本节对应从基线 `c96f32b` 开始的早期离散曲线工作树。该中间阶段把连续 `x → V`、短按百分比和 dB 量化改为均匀控制点横轴与整数 index 状态表；最终版本已经继续演进为“控制点自由 x、只有按键位置均匀”，语义以上方最新记录和 [CURVE_EDITOR.md](CURVE_EDITOR.md) 为准。下方更早记录中的“1% / 0.7% / 40% 短按步长”是当时安装包的历史配置名称，不代表当前界面仍提供这些选项。

### 主机门禁

最终源码执行以下门禁并全部成功：

```powershell
. .\scripts\android-env.ps1
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin `
  :app:lintDebug :app:lintRelease :app:assembleDebug `
  :app:assembleRelease :app:bundleRelease --rerun-tasks
```

覆盖包括 `K` 次按键 / `K+1` 状态不变量、严格整数投影、点数变化、0–15 / 0–150 / 非零 min / fixed-range、画笔推挤、离散 reducer、旧设置迁移、exact-index coordinator 连续性和 Android 测试源码。Debug/Release lint 均无阻断项，Debug APK、R8 Release APK 与 Release AAB 均生成成功。

### API 37 模拟器

验证 AVD 为 `VolumeMapper_API_37`，序列号 `emulator-5554`，build fingerprint 为 `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys`。

- 全量 instrumentation：`MainActivityTest`、`RealSystemVolumeE2eTest`、`VolumeKeyAudioIntegrationTest` 共 3 项，结果 `OK (3 tests)`；最终 UX 提示调整后又定向重跑 `MainActivityTest`，结果 `OK (1 test)`。
- 宿主 `emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild` 从空白应用数据完成显著披露、真实 AccessibilityService、`specialUse` FGS、通知停止和内核 evdev 按键链路，结果为后台映射 `5 → 11 → 5`；停止后检测到系统 `AudioService.adjustSuggestedStreamVolume`，fail-open 通过。
- 使用 Android CLI 分别检查曲线主画布和精调控件：固定等距列、选中点、`Δindex` 条带、次数设置、index 输入与撤销/重做在 1080×2400 模拟器上未见裁切或不可达；本地截图保存在被 Git 忽略的 `artifacts/curve-editor-*.png`。
- 最终只读清理复核：adbd 为 `uid=2000(shell)`，`enabled_accessibility_services=null`，目标服务列表为空，媒体音量为 `5/15`。

本轮结束时先前的小米真机序列号 `112594e4` 已不在 `adb devices` 中，因此没有把这一版 APK 重新安装到真机，也不把旧安装包的真机结果冒充为本次离散曲线通过。重新连接后仍需补一次覆盖安装、设置迁移、0–150 曲线编辑和实体按键回归。

## 2026-08-23 至 2026-08-24 Xiaomi 15 真机验证

验证设备为 Xiaomi 15，HyperOS，Android 16 / API 36。本节结果只代表这一台手机、当前 ROM 和本次连接的输出设备，不外推到其他小米设备、其他厂商或同名耳机。蓝牙设置显示的名称为“Xiaomi Buds 5 Pro”，但没有取得可验证的硬件型号，因此下文只称其为该显示名称的耳机，不能据此确认具体型号。

### 扬声器与实体按键

系统运行时报告扬声器媒体音量范围为 `0..150`。在线性曲线、1% 短按步长、按 index 量化配置下，实体音量键得到以下结果：

| 场景 | 结果 |
|---|---|
| Activity 位于前台 | `62 → 63 → 62`，`AudioService` 记录的 `setStreamVolume` 调用方为 `dev.spcdts.volumemapper.debug` |
| Activity 位于后台、显示桌面 | `62 → 63 → 62` |
| 亮屏锁屏，`showing=true`、`inputRestricted=true` | `62 → 63 → 62` |
| 熄屏 Doze、无媒体播放 | 按键未触发应用或系统音量写入，媒体音量保持 `62`；该场景不记为映射通过 |

目标无障碍服务与设备原有的另一个无障碍服务同时保持 Bound，未观察到服务崩溃。`specialUse` FGS 正常运行，持续通知和停止入口可见。

### 蓝牙 A2DP 样本

连接上述显示名称为“Xiaomi Buds 5 Pro”的耳机后，系统实际选择 A2DP / AAC 路由，AVRCP 绝对音量为 `true`，Android 媒体范围仍为 `0..150`，测试起始 index 为 `8`：

- 手机实体侧键完成 `8 → 9 → 8`，并观察到 `setAvrcpVolume` 同步；
- 耳机自身音量手势没有进入本应用的无障碍按键链路，而是由 `com.android.bluetooth` 直接完成 `8 → 18` 和 `18 → 8`，单次变化为 10 个 Android index；
- 外部音量变为 `18` 后，再按手机侧键得到 `18 → 19`，证明应用会从当前回读值继续映射，而不是沿用旧的逻辑音量。

以上只证明无媒体播放时该次 A2DP / AAC、AVRCP 绝对音量链路的 index 同步，LE Audio 尚未验证。

### 真实媒体播放暴露的失败

在哔哩哔哩实际播放视频、系统报告媒体会话 active 且 `mIsPlaying=true`、测试人员确认耳机可听见声音后，将本应用 Activity 置于后台并短按手机音量加，观察到：

1. HyperOS 在 Accessibility 按键分派等待窗口内没有取得本应用回调，日志记录 key dispatch timeout；
2. 系统默认路径随后把 A2DP index 从 `8` 调到 `18`，又调到 `28`；
3. 约 81 秒后重新打开本应用 Activity 时，旧安装包才收到此前的 `DOWN` / `UP`，并额外写成 `29`、`31`。

该场景判定为失败，不计入精细映射通过。AMS 当时仍把本应用记录为带前台服务的进程，`procState=4`、`cached=false`、`isFrozen=false`；现有证据只能说明 HyperOS 延迟并超时了无障碍按键回调，不能宣称进程已被冻结。

针对迟到事件，当前工作树新增 500 ms 新鲜度边界：过期初始 `DOWN` 不接管也不入队；匹配现有 owner 的过期尾事件会原子清 owner、关闭可写资格、提升 epoch、取消 ticker/verification 并排空 actor 手势，不再进入 reducer 执行短按收尾写。500 ms 来自 [AOSP Accessibility `KeyEventDispatcher`](https://android.googlesource.com/platform/frameworks/base/+/master/services/accessibility/java/com/android/server/accessibility/KeyEventDispatcher.java) 的固定等待时间。纯 JVM 测试已覆盖 499 ms / 500 ms 边界、负 age、溢出以及各类事件处置；修复版真机对照结果见下一节。

### 修复版与省电策略对照复测

覆盖安装修复版后，原 DataStore 设置和两项已启用的无障碍服务均保留；目标服务自动重新 Bound，`START_NOT_STICKY` 映射 FGS 没有随安装自动重启。应用从可见 Activity 重新启动 FGS 后，持续通知再次出现。诊断页新增的公开应用详情入口正确落到本应用的 HyperOS 页面，且没有申请新权限或自动改系统设置。

当时 HyperOS 为本应用选择“智能限制后台运行（推荐）”。测试人员手动改为“无限制”后，在相同 A2DP 路由、起始 index `8` 下重新执行：

- 哔哩哔哩为 `topResumedActivity`，媒体会话 `active=true`、`PLAYING`，蓝牙栈 `mIsPlaying=true`，测试人员确认耳机可听见声音；
- 手机音量加 `DOWN` 时间为 `00:34:42.241`，应用在 `00:34:42.398` 调用 `setStreamVolume(index=9)`，约 157 ms；
- `AudioService` 只记录应用的 `8 → 9` 和对应 `setAvrcpVolume`，没有系统默认 `+10/+20`；
- 随后把映射器 Activity 切回前台，音量仍为 `9`，没有迟到副本或第二次应用写入；再用 0.7% 短按步长恢复为原始 `8`。

同一时刻 HyperOS 日志仍打印多条 `application accessibility dispatch key timeout`，但紧接着明确记录目标服务 `handled this event`；按实际 event time 到应用写入的时差，本次事件属于 500 ms 窗口内的新事件。该对照同时改变了应用代码和省电策略，且只有一次样本，因此只能把“当前组合在这一次真实播放中通过”作为结论，不能断言“无限制”单独构成充分修复。过期事件防护的边界与处置由 8 个 JVM 测试验证，本次没有人为制造过期物理事件。

### 真机测试工具限制

本机调试确认，`android layout` / UiAutomator 会在该 HyperOS 设备上短暂解绑再重绑已启用的无障碍服务，因此不能用它观察实体按键测试过程；本轮改用普通截图、`dumpsys` 和日志进行只读核验。该现象属于测试工具干扰，不计为应用服务断连。

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

### 2026-08-24 最终工作树构建与真机收尾

在迟到 KeyEvent 保护、备份规则和跨 OEM 应用详情入口合入后，使用 `--rerun-tasks` 强制执行 `testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease bundleRelease`：51/51 JVM 测试通过，Debug/Release lint 均为 0 error、3 个依赖版本提醒，Debug/Release APK 与 AAB 构建成功。

| 当前工作树产物 | 字节数 | SHA-256 | 签名状态 |
|---|---:|---|---|
| `app-debug.apk` | 64,760,279 | `8F5A393491B41C1ECE531E3F7F53CD71D852BD0EB587281C3AC947F005BAA863` | Android Debug 证书 |
| `app-release-unsigned.apk` | 2,289,237 | `BA8378A871B87FF15A8BE27AEC6366B7B00B15112BF09C5FFDB16617169C90AE` | 未签名；`apksigner` 明确不通过 |
| `app-release.aab` | 3,124,210 | `9743E528484D34C86F1968174E726EF36FF453C96D7A360E3E1FB6CD19F2EB42` | 未签名；`jarsigner` 输出 `jar is unsigned` |

最终 Debug APK 在 Xiaomi 15 上再次覆盖安装成功。更新后，目标无障碍服务自动重新 Bound，设备原有的另一项无障碍服务仍保留，`START_NOT_STICKY` 映射 FGS 没有自动重启；A2DP 恢复并保持原始 `8/150`，扬声器保存值仍为 `62/150`。最终 APK 的公开应用详情入口再次正确打开目标应用页面；随后执行一次“启动映射 → FGS/持续通知出现 → 停止映射 → FGS/活动通知消失”烟测，音量未改变，无障碍服务保持 Bound 且控制器停止。

对当前未签名 Release APK 的 `aapt2` 黑盒检查确认：合并 Manifest 同时引用 `fullBackupContent` 与 `dataExtractionRules`；资源收缩后的两份 XML 仍分别排除 `datastore/volume_mapper.preferences_pb`，API 31+ 规则同时覆盖 cloud backup 和 device transfer。生产签名后仍需对最终上传产物重复这一检查。

`play-policy-insights` 自动审计在 Data Safety、账号和受限权限范围内给出绿色 `Compliant`，未发现数据外传或高风险权限违规；唯一建议项是必须在 Play Console 完成 `specialUse` FGS 声明并提供功能说明和演示材料。该自动报告不构成法律意见或 Play 审核保证，也不能替代 Accessibility API 声明与人工审核。

### 尚未通过的发布门槛

- 上述小米/HyperOS 单机样本已覆盖扬声器前台、桌面后台、亮屏锁屏及一副耳机的空载 A2DP 绝对音量；真实媒体播放最初因后台无障碍回调超时失败，修复版加“无限制”策略后的单次对照通过；LE Audio VCS、安全音量提示和其他设备/ROM 仍未执行；
- API 31/32、API 33–35 以及 Pixel、Samsung、OPPO/vivo/Honor 真机尚未执行；
- 来电、闹钟、相机、截图组合键、锁屏、省电和 ROM 杀后台仍需真机矩阵；
- 备份规则已同时覆盖 API 30 及以下完整备份与 API 31 及以上云备份/设备迁移，并排除包含 `disclosureAccepted` 的整个设置 DataStore；当前未签名 Release 已完成打包检查，生产签名产物仍需复核；
- Accessibility API、`specialUse` FGS、Data safety 与隐私政策仍需 Play Console 审核，自动测试不能代替政策批准。

## 2026-08-21 基线记录

提交 `d118c3f523a79f18e58cac09683271b6092736d4` 当时完成了 35/35 JVM 单测、Debug lint 0 error、API 28 与 API 37 各 2/2 instrumentation，以及未签名 Release APK/AAB 构建。该轮设备测试没有模拟系统向 AccessibilityService 分派实体按键；本次 2026-08-22 E2E 已补上真实服务、FGS、通知和 evdev 按键链路。
