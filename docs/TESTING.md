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

- `StepVolumeMap` 的非法整数控制几何、均匀按键网格插值与严格整数最优投影、修改按键次数后的 x 重投影、拖动越界时对相邻点的整数推挤、非零/缩容/固定路由绑定与重基准，以及选段中点插入；指定点删除闭环由设备侧 UI 测试覆盖；
- `VolumeMappingReducer` 的外部 index 严格相邻步进、299/300 ms 长按起效边界、长按积分对 ticker 节奏不敏感、匹配与错误方向 UP、`SynchronizeObserved` 读回重锚、`CancelPress`，以及固定音量路由不发起平台写入；
- 设置 `v4` 整数坐标 round-trip、旧 `hold_delay` 忽略与清理、`v1` / `v2` / `v3` 与 legacy fallback、全部 `1..150` 按键次数的 downgrade shadow、坏字段独立降级、非即时写尾沿合并、immediate 回执和一次 `IOException` 后继续处理；
- 空闲映射位置只在快照一致时保留、固定范围判定与快照接受后的 ready 状态、普通命令优先且 tick 只保留最新值的 mailbox、已释放或已被新手势替换的 tick owner 处置，以及无障碍按键过期边界与 owned gesture 处置。

仓库恰好保留 20 个 JVM 测试和 10 个 Android instrumentation / E2E 测试，共 30 个唯一 `@Test` 入口。JVM 测试不会创建真实 AccessibilityService、FGS、AudioManager 路由回调，也不覆盖宿主 evdev 注入链路；未在上述清单列出的历史细节不能仅凭当前 JVM 套件宣称已自动验证。

### Lint 与构建

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:bundleRelease
```

Release 默认可生成未签名 AAB；正式签名见 `docs/RELEASE.md`。

### Android instrumentation

仓库包含三个设备侧测试类：

- `MainActivityTest`：共 8 项，分别验证正式单页、扩大后的图表、首次中点选择与当前音量语义，应用菜单中的隐私政策和版本信息，选段插入和选新增点删除闭环，普通点右插与末点左插，K 变化时的选择与容量，控制点 x/y 拖动吸附，点/线段动态无障碍操作，以及无障碍整数移动；测试结束会恢复进入测试前的完整设置；
- `VolumeKeyAudioIntegrationTest`：在可见 Activity 中直接把 coordinator 标记为 Accessibility/FGS 已连接，构造初始 DOWN、同 token repeat、跨过 300 ms 起效阈值的持续按住和最终 UP，并验证真实 `STREAM_MUSIC` index 到达连续目标且最终清理。
- `RealSystemVolumeE2eTest`：在模拟器空白应用数据下通过真实 Compose UI 验证未勾选时不能同意、取消后不保存/不启动、再次触发后主动同意，并确认同意后直接进入系统无障碍设置；若同意状态已持久化，则验证已同意路径。随后确认应用未声明通知权限、真实绑定 AccessibilityService，并验证 `specialUse` FGS 仍能正常启动；宿主 E2E 会先执行 `pm clear`，再复用它准备包含 40% 跨度的确定性整数控制点映射。

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

`emulator-e2e-test.ps1` 只允许 `emulator-*`。它从空白应用数据开始，完成真实披露 UI，绑定目标无障碍服务，从可见 Activity 启动控制器并验证 FGS；API 33+ 会实际展开 SystemUI、确认目标标题不在通知抽屉。随后脚本进入最近任务并向上划掉本应用卡片，确认 FGS 与无障碍仍在，再从 Linux evdev 注入后台音量加/减键，最后重新打开应用通过主开关停止并验证 fail-open。脚本会临时把可调试模拟器的 adbd 切到 root，并在 `finally` 中恢复原无障碍配置、媒体/铃声音量、通知面板和 adbd 身份；任何清理失败都会使脚本失败。应用数据会在测试开始和结束时被清空，不能恢复测试前内容。

普通 `connectedDebugAndroidTest` 重复运行时会保留 Debug 应用数据，因此已接受披露的 AVD 可能跳过披露页面。需要确定性验证首次披露时，应运行宿主 E2E；它会在安装测试 APK 后清空应用数据，再执行定向 instrumentation。

不能用 `adb shell input keyevent 24/25`、`UiDevice.pressKeyCode()` 或 `UiAutomation.injectInputEvent()` 代替上述按键阶段；这些注入路径会跳过 Accessibility input filter，无法证明 `VolumeKeyAccessibilityService.onKeyEvent()` 收到了系统按键。宿主脚本会动态查找声明 `KEY_VOLUMEUP` 的 `/dev/input/event*`，再使用 `sendevent` 产生 DOWN/SYN/UP/SYN。

模拟器上至少验证：

1. 显著披露必须主动勾选后才能同意；取消时不得保存同意或启动控制器，再次点击主开关应重新显示披露，同意后应直接进入系统无障碍设置；
2. 未启动 FGS 时音量键由系统处理；
3. 启动 FGS 且无障碍连接后，evdev `KEY_VOLUMEUP/KEY_VOLUMEDOWN` 按曲线变化；
4. 快速 tap 与长按不会因 repeat 频率改变数学结果；
5. 路由/服务停止后新手势 fail-open；
6. API 33+ 的控制器标题不进入普通通知抽屉，API 28–32 仍显示平台要求的 FGS 通知；应用主开关在所有版本都能立即释放按键；
7. 旋转、进程重建后设置仍在 DataStore 中。

曲线界面还应目视验证：短且可插入的线段仍能在两端控制点触摸热区之间被选中；点选择显示坐标虚线，线段选择只强调对应折线；新增后选中新点，删除后选中合并线段；浅色、深色和窄屏下绘图区没有裁切或文字重叠。启动器页需分别检查普通 adaptive icon、圆形 mask 和 Android 13+ themed icon，确认黑底白色上升曲线、等大节点与单个绿色光晕在小图标下仍可辨认。

### API 28–37 差异点

| API | 必测实现分支 | 不能由其他 API 代替的原因 |
|---|---|---|
| 28–30 | MediaRouter/连接设备启发式路由、active hold 500 ms mode/route guard、FGS 与通知 | 没有公开 `OnModeChangedListener`，也没有 API 33 媒体属性路由查询 |
| 31–32 | mode listener、启发式路由、`isAccessibilityTool=false` 的 v31 XML | 有 mode listener，但媒体 route 仍非 API 33 的精确查询 |
| 33–36 | `getAudioDevicesForAttributes()` 的 confirmed/ambiguous 分支、无通知权限的 modern FGS 行为 | 可能取得可信单一路由和 dB 表，也可能多设备降级 heuristic |
| 37 | target 37 的 Android 17 后台音量 hardening、visible Activity → `specialUse` FGS、静默拒绝后的回读/fail-open | 这是目标平台新增约束，低版本结果不能外推 |

仓库的三个 AVD 覆盖 API 28、36 与 37，API 36 代表性覆盖公开媒体路由与 API 33+ 静默 FGS 分支；API 31/32、API 33–35 以及 OEM ROM 仍应通过额外模拟器或对应真机验证。

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
- 只有 active press 存在 20 ms latest-only ticker，结束后无常驻 tick；普通命令优先于 tick，最短 60 ms 长按配置不会因调度积压形成尾随调整；
- disarm、FGS stop、settings、手动刷新和 route/environment 变化先原子失效 control epoch；route 变化还失效 route epoch，旧 I/O 结果不能复活；
- active hold 每约 500 ms 核对 mode、route ID、范围与 fixed-volume，且 guard 不覆盖 active logical/expected index；
- 一个 verification cycle 跟踪 latest expected；fresh mismatch 不重锚，成熟 mismatch 清 pending 并同步 observed，final mismatch 才计失败；
- UP 必须刷新最终 reducer target；同一 route ID 的 min/max 变化必须取消手势，只有 dB 诊断元数据变化不能重建 reducer；
- 三次连续读写/最终回读失败后新按键自动交还系统；
- 设置中按键次数 `K` 可输入，控制点数 `P` 只读显示并通过上下文相关的 `−` / `+` 修改；改 `K` 保持 P 与 y 并按旧比例严格重投影整数 x，改 `P` 不改变 `K`；
- 点与线段选择互斥；`+` 在选中线段中插入，或在选中点的右侧段插入（末点改用左侧段），且目标段必须具有空余整数 X/Y；`−` 只删除选中的内部点，端点不可删除；
- `P` 个控制点与 `K+1` 个实际按键状态都固定端点，运行时 index 严格递增；
- 当前路由跨度小于 `K` 时只临时降低有效按键次数；编辑 K 或 P 都不能顺带固化临时路由结果或破坏另一个参数；
- 不读取窗口内容，不使用隐藏 API，不绕过系统安全音量。
