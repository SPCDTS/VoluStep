# 测试说明

## 结果记录原则

仓库包含测试源码、AVD 定义和辅助脚本，但“可以编译”“存在测试”“AVD 已创建”都不等于设备测试已经执行通过。提交测试结论时必须记录 commit/工作区状态、日期、API、镜像或 ROM build、设备/耳机、执行命令以及 pass/fail/skip；本文只描述覆盖和运行方法，不声明任何 API 或真机的当前通过状态。每轮本机执行结果单独记录在 [VERIFICATION.md](VERIFICATION.md)。

## 自动测试层次

### JVM 单元测试

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

当前覆盖：

- 按键次数 `K` / 控制点数 `P`、严格递增的整数按键位、整数端点与折线采样；
- `K+1` 个均匀按键位置、修改 K 时的整数重投影、`2≤P≤K+1`、跨路由范围重投影、全局最小平方结果及确定性 tie-break；
- x/y 联合编辑、相邻约束、端点固定与拖动越界时的最小推挤；
- 精确状态短按、外部 index 的严格上下界选择、固定长按间隔、repeat no-op、匹配/不匹配 UP、readback 同步与取消；
- 不同 50 ms ticker 节奏下固定间隔积分的一致性，以及 `60…500 ms`、20 ms 网格配置边界；
- 非零 min、固定音量、0–15 与 0–150 路由绑定边界；
- mapping state 为空、外部 index 变化、精确状态连续性和 active hold 步数余量边界；
- DataStore `v4` 整数坐标 round-trip、`v1` / `v2` / `v3` 保形迁移、legacy shadow 恢复、旧间隔对齐与坏字段独立降级；
- 设置写入 actor 的 FIFO immediate barrier、普通快照防抖、回执顺序和单次持久化失败后继续工作。

上述细粒度断言合并在 22 个 JVM 场景组内；加上 8 个 Android instrumentation，仓库共保留 30 个测试入口。UI 在 DataStore 初始快照原子发布前禁用配置写入口，避免默认占位值覆盖已保存设置。JVM 测试不会创建真实 AccessibilityService、FGS、AudioManager 路由回调，也没有用 fake backend 覆盖 coordinator 的完整并发时序。

### Lint 与构建

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:bundleRelease
```

Release 默认可生成未签名 AAB；正式签名见 `docs/RELEASE.md`。

### Android instrumentation

仓库包含三个设备侧测试类：

- `MainActivityTest`：共 6 项，验证正式单页只保留核心控件、当前音量水平线语义、`K` / `P` 分别编辑及动态容量约束、控制点 x/y 拖动与吸附、图表无障碍选择/移动操作、`60…500 ms` 长按间隔边界，以及默认折叠的设备区；测试结束会恢复进入测试前的完整设置；
- `VolumeKeyAudioIntegrationTest`：在可见 Activity 中直接把 coordinator 标记为 Accessibility/FGS 已连接，构造完整 DOWN/UP，并验证真实 `STREAM_MUSIC` index 改变和最终清理。
- `RealSystemVolumeE2eTest`：在模拟器空白应用数据下通过真实 Compose UI 完成显著披露；若同意状态已持久化，则验证已同意路径。随后真实绑定 AccessibilityService、启动 `specialUse` FGS、检查常驻通知，并只在本应用通知行内展开和点击停止 action；宿主 E2E 会先执行 `pm clear`，再复用它准备包含 40% 跨度的确定性整数控制点映射。

第二项测试不会启动真实 AccessibilityService，也不会验证系统是否把物理按键分派给服务。第三项的 instrumentation 阶段不能独自证明按键分派，因为 UiAutomation 注入会绕过 Accessibility input filter；实体按键链路由下述宿主 E2E 使用内核 evdev 事件验证。模拟器结果仍不能替代 OEM、蓝牙耳机和真实系统授权页测试。编译测试 APK 与实际执行应区分：

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:connectedDebugAndroidTest
```

第一条只证明 androidTest 源码可编译，第二条才会在当前连接设备上执行。使用定向测试时也要记录完整 runner 输出和 skip/assumption；被 assumption 跳过不能记为通过。

### API 28 / 36 / 37 模拟器

```powershell
.\scripts\create-avds.ps1
.\scripts\start-emulator.ps1 -Api 37
.\gradlew.bat :app:connectedDebugAndroidTest
.\scripts\emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild
```

`emulator-e2e-test.ps1` 只允许 `emulator-*`。它从空白应用数据开始，完成真实披露 UI，绑定目标无障碍服务，从可见 Activity 启动控制器，验证前台通知，退到后台后从 Linux evdev 注入音量加/减键，并在本应用标题所属的 SystemUI 通知行内执行停止 action 后验证 fail-open。脚本会临时把可调试模拟器的 adbd 切到 root，并在 `finally` 中恢复原无障碍配置、媒体/铃声音量和 adbd 身份，同时收起通知面板；任何清理失败都会使脚本失败。应用数据会在测试开始和结束时被清空，不能恢复测试前内容。

普通 `connectedDebugAndroidTest` 重复运行时会保留 Debug 应用数据，因此已接受披露的 AVD 可能跳过披露页面。需要确定性验证首次披露时，应运行宿主 E2E；它会在安装测试 APK 后清空应用数据，再执行定向 instrumentation。

不能用 `adb shell input keyevent 24/25`、`UiDevice.pressKeyCode()` 或 `UiAutomation.injectInputEvent()` 代替上述按键阶段；这些注入路径会跳过 Accessibility input filter，无法证明 `VolumeKeyAccessibilityService.onKeyEvent()` 收到了系统按键。宿主脚本会动态查找声明 `KEY_VOLUMEUP` 的 `/dev/input/event*`，再使用 `sendevent` 产生 DOWN/SYN/UP/SYN。

模拟器上至少验证：

1. 显著披露必须主动勾选后才能同意；
2. 未启动 FGS 时音量键由系统处理；
3. 启动 FGS 且无障碍连接后，evdev `KEY_VOLUMEUP/KEY_VOLUMEDOWN` 按曲线变化；
4. 快速 tap 与长按不会因 repeat 频率改变数学结果；
5. 路由/服务停止后新手势 fail-open；
6. API 37 上前台服务通知可见，停止 action 立即释放按键；
7. 旋转、进程重建后设置仍在 DataStore 中。

### API 28–37 差异点

| API | 必测实现分支 | 不能由其他 API 代替的原因 |
|---|---|---|
| 28–30 | MediaRouter/连接设备启发式路由、active hold 500 ms mode/route guard、FGS 与通知 | 没有公开 `OnModeChangedListener`，也没有 API 33 媒体属性路由查询 |
| 31–32 | mode listener、启发式路由、`isAccessibilityTool=false` 的 v31 XML | 有 mode listener，但媒体 route 仍非 API 33 的精确查询 |
| 33–36 | `getAudioDevicesForAttributes()` 的 confirmed/ambiguous 分支、通知权限与 modern FGS 行为 | 可能取得可信单一路由和 dB 表，也可能多设备降级 heuristic |
| 37 | target 37 的 Android 17 后台音量 hardening、visible Activity → `specialUse` FGS、静默拒绝后的回读/fail-open | 这是目标平台新增约束，低版本结果不能外推 |

仓库的三个 AVD 覆盖 API 28、36 与 37，API 36 代表性覆盖公开媒体路由与通知权限分支；API 31/32、API 33–35 以及 OEM ROM 仍应通过额外模拟器或对应真机验证。

当前持续开发门禁以 API 36/37 和后续小米/蓝牙真机为优先。API 28 因项目仍声明 `minSdk=28` 而保留基础安装、启动和关键链路检查，但不为旧系统增加与产品目标无关的专用行为；若以后提高 minSdk，应同步删除对应分支和 AVD，而不是继续累积兼容代码。

Android 17 还应使用系统支持的音频 hardening 调试命令（若该镜像提供），观察 `AudioHardening` 日志，并分别验证允许、静默拒绝和抛错模式。

## 小米与其他真机

真机必须手动启用无障碍，避免脚本覆盖用户已有服务。建议流程：

在真机实体按键验证期间不要运行 `android layout` 或其他依赖 UiAutomator 的 UI 层级采集。本次 HyperOS 实测中，它会短暂解绑再重绑设备上已启用的无障碍服务，从而污染服务连续性和按键分派结果。观察界面应使用普通截图，状态核验使用 `dumpsys` / logcat；按键必须由测试人员实际按下。

真实播放测试必须同时记录按键 `eventTime`、无障碍回调到达时间、系统默认调节和应用 `setStreamVolume` 写入。若回调 age 已达到 500 ms，修复版应放行过期初始 `DOWN`；若已有手势 owner，则只排空匹配的迟到尾事件，不能执行短按收尾写。对 OEM 省电策略的任何调整都要记录调整前后对照结果，不能把设置页面已打开当作兼容性通过。

1. `adb devices -l` 记录序列号；
2. `adb -s SERIAL install -r app\build\outputs\apk\debug\app-debug.apk`；
3. 在手机 UI 中完成披露、无障碍和通知授权；
4. 依次测试扬声器、A2DP、LE Audio（若支持）、有线/USB；
5. 记录短按 20 次、长按 5 秒、快速反向和边界音量；
6. 测试熄屏/锁屏、蓝牙断连重连、省电、进程清理、系统重启；
7. 测试来电、通话、闹钟、相机、截图组合键、另一个无障碍服务共存；
8. 测试耳机自身音量键，标记它是 KeyEvent 还是远端绝对音量；
9. 采集诊断：

```powershell
.\scripts\capture-device-diagnostics.ps1 -Serial SERIAL
```

诊断输出位于被 Git 忽略的 `artifacts/`。上传前应检查 `getprop` 和 logcat 是否含设备标识或其他应用信息。

## 验收标准

- Accessibility 回调中不进行文件、网络或 AudioManager 写入；
- 新手势只在 FGS、无障碍、media-safe、路由和后端全部健康，且有界队列接受 DOWN 时消费；
- 每次手势只允许一个 `(deviceId, keyCode, downTime)` owner；repeat 只刷新 heartbeat，绝不入队或重启取消后的手势；
- 一旦消费 DOWN，普通失效仍消费到同 token 的 UP；Accessibility 断连清 owner，丢 UP 在约 2 秒 watchdog 后可恢复；
- 只有 active press 存在 50 ms ticker，结束后无常驻 tick；写入门限不超过一个 tick，最短 60 ms 长按配置不能因限流合并相邻状态；
- disarm、FGS stop、settings、手动刷新和 route/environment 变化先原子失效 control epoch；route 变化还失效 route epoch，旧 I/O 结果不能复活；
- active hold 每约 500 ms 核对 mode、route ID、范围与 fixed-volume，且 guard 不覆盖 active logical/expected index；
- 一个 verification cycle 跟踪 latest expected；fresh mismatch 不重锚，成熟 mismatch 清 pending 并同步 observed，final mismatch 才计失败；
- UP 必须刷新最终 reducer target；同一 route ID 的 min/max 变化必须取消手势，只有 dB 诊断元数据变化不能重建 reducer；
- 三次连续读写/最终回读失败后新按键自动交还系统；
- 设置中按键次数 `K` 与控制点数 `P` 分别编辑；改 `K` 保持 P 与 y 并按旧比例严格重投影整数 x，改 `P` 不改变 `K`；
- `P` 个控制点与 `K+1` 个实际按键状态都固定端点，运行时 index 严格递增；
- 当前路由跨度小于 `K` 时只临时降低有效按键次数；编辑 K 或 P 都不能顺带固化临时路由结果或破坏另一个参数；
- 不读取窗口内容，不使用隐藏 API，不绕过系统安全音量。
