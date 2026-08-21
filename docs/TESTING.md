# 测试说明

## 结果记录原则

仓库包含测试源码、AVD 定义和辅助脚本，但“可以编译”“存在测试”“AVD 已创建”都不等于设备测试已经执行通过。提交测试结论时必须记录 commit/工作区状态、日期、API、镜像或 ROM build、设备/耳机、执行命令以及 pass/fail/skip；本文只描述覆盖和运行方法，不声明 API 28、API 37 或任何真机的当前通过状态。2026-08-21 的本机执行结果单独记录在 [VERIFICATION.md](VERIFICATION.md)。

## 自动测试层次

### JVM 单元测试

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

当前覆盖：

- 曲线端点、单调性、分段插值、反解与 plateau bias；
- 拖动控制点时的相邻约束、增删点和曲线积分；
- 短按、长按延迟、加速、repeat no-op、匹配/不匹配 UP、readback 同步与取消；
- 单次大跨度积分与 50 ms 多次积分的一致性；
- 非零 min、0–15 快照边界、0–150 启发式路由、重复/不可用 dB 与无 dB 降级量化；
- mapping state 为空、外部 index 变化和 active state 时的小档位余量保留边界；
- DataStore 设置 round-trip、旧格式迁移和坏字段独立降级。

这些是 pure reducer/quantizer/serialization 测试，不会创建真实 AccessibilityService、FGS、AudioManager 路由回调，也没有用 fake backend 覆盖 coordinator 的完整并发时序。

### Lint 与构建

```powershell
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:bundleRelease
```

Release 默认可生成未签名 AAB；正式签名见 `docs/RELEASE.md`。

### Android instrumentation

仓库包含两项设备侧测试：

- `MainActivityTest`：验证主导航、曲线编辑器、Slider 与预设控件可达；
- `VolumeKeyAudioIntegrationTest`：在可见 Activity 中直接把 coordinator 标记为 Accessibility/FGS 已连接，构造完整 DOWN/UP，并验证真实 `STREAM_MUSIC` index 改变和最终清理。

第二项测试不会启动真实 AccessibilityService，也不会验证系统是否把物理按键分派给服务；它同样不能替代 Android 17 后台 hardening、通知、系统授权页或蓝牙耳机真机测试。编译测试 APK 与实际执行应区分：

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:connectedDebugAndroidTest
```

第一条只证明 androidTest 源码可编译，第二条才会在当前连接设备上执行。使用定向测试时也要记录完整 runner 输出和 skip/assumption；被 assumption 跳过不能记为通过。

### API 28 / 37 模拟器

```powershell
.\scripts\create-avds.ps1
.\scripts\start-emulator.ps1 -Api 37
.\scripts\emulator-smoke-test.ps1 -Serial emulator-5554
```

`emulator-smoke-test.ps1` 只允许 `emulator-*`，因为它会修改 secure accessibility settings。它不会自动修改真机无障碍配置，也不会自动同意披露、启动控制器、发送按键或断言音量；脚本结束后的验证清单是人工步骤。

模拟器上至少验证：

1. 显著披露必须主动勾选后才能同意；
2. 未启动 FGS 时音量键由系统处理；
3. 启动 FGS 且无障碍连接后，`input keyevent 24/25` 按曲线变化；
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

仓库的两个 AVD 只覆盖 API 28 与 37 两端；API 31/32 和 33–36 的分支仍应通过额外模拟器或对应真机验证。

Android 17 还应使用系统支持的音频 hardening 调试命令（若该镜像提供），观察 `AudioHardening` 日志，并分别验证允许、静默拒绝和抛错模式。

## 小米与其他真机

真机必须手动启用无障碍，避免脚本覆盖用户已有服务。建议流程：

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
- 只有 active press 存在 50 ms ticker，结束后无常驻 tick；连续长按写入稳态不超过约 14 次/秒；
- disarm、FGS stop、settings、手动刷新和 route/environment 变化先原子失效 control epoch；route 变化还失效 route epoch，旧 I/O 结果不能复活；
- active hold 每约 500 ms 核对 mode、route ID、范围与 fixed-volume，且 guard 不覆盖 active logical/expected index；
- 一个 verification cycle 跟踪 latest expected，首次 mismatch 立即同步 observed，final mismatch 才计失败；
- 三次连续读写/最终回读失败后新按键自动交还系统；
- 不读取窗口内容，不使用隐藏 API，不绕过系统安全音量。
