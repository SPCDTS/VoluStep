# 架构说明

## 目标与约束

应用要把手机实体音量键的离散事件转换为用户定义的连续映射，同时接受 Android 音频链路最后仍是整数档位的事实。核心状态方程是：

```text
初始 DOWN → KeyToken(deviceId, keyCode, downTime)
repeat → 只刷新 heartbeat；UP → 结束同一 token
        ↓
x(t) ∈ {-1, 0, +1}
        ↓  仅 active press 启动 50 ms ticker，不依赖 OEM key-repeat 频率
逻辑位置 u(t) ∈ [0, 1]
        ↓  用户可视化曲线 F(u)
连续目标 V ∈ [0, 1]
        ↓  当前路由 dB 表或 index 表量化
STREAM_MUSIC 整数 index
        ↓  setStreamVolume + 单 verification cycle 的约 80/380 ms 回读
AudioPolicy → 蓝牙 AVRCP/VCS → 耳机固件
```

曲线采用单调分段线性插值。普通 cubic spline 容易在控制点间过冲并破坏单调性，因此首版不采用。

## 代码边界

```text
core/
  MappingCurve             纯 Kotlin 单调曲线、反解、拖点约束、积分
  VolumeMappingReducer     短按/长按/UP 的纯状态机；orphan repeat 严格 no-op
  VolumeQuantizer          连续目标到 index/dB 阶梯的量化
  RouteVolume              路由、范围、dB 表和快照模型

audio/
  AudioManagerVolumeBackend 公开 API 路由探测、范围缓存、读写和环境回调

runtime/
  VolumeKeyAccessibilityService  只把 KeyEvent 交给 coordinator，不读取窗口内容
  MappingControllerService       Android 17 specialUse FGS
  MappingCoordinator             token owner、双 epoch、单 actor、节流、回读、fail-open

data/
  SettingsRepository       DataStore 持久化，拖动期间防抖写入

ui/
  CurveEditor              连续曲线、实际离散阶梯和当前位置
  VolumeMapperApp          控制、设置、披露和设备诊断
```

所有组件都在单进程中，避免 AccessibilityService、前台服务和 UI 各自持有不同状态。

## 按键一致性

`onKeyEvent()` 必须尽快返回。回调只读取原子缓存，构造 `KeyToken(deviceId, keyCode, downTime)`，以 CAS 取得单一 owner，并用有界 `Channel(64)` 的 `trySend` 投递首次 DOWN。它不会调用会触发 Binder 的 AudioManager 查询或写入。

一次新的 DOWN 仅在以下条件全部满足时消费：

- 用户已从可见 Activity 启动前台控制器；
- AccessibilityService 已连接；
- 当前不是固定音量、故障放行或通话音频模式；
- 已有有效媒体路由快照；
- actor 队列接受首次 DOWN，且入队前 control/route epoch 没有变化。

DOWN 一旦消费，同一 token 的 repeat 只更新 atomic heartbeat，不进入 actor；UP 会排空已消费手势，`trySend` 失败时改用可挂起的异步 `send`。disarm、FGS stop、settings、手动刷新、路由变化或 fail-open 会立刻禁止新手势并取消 active/pending/verification，但保留 owner 到 UP；Accessibility 断连会立即清 owner，因为服务已不可能继续接收完整事件流。

Android 各厂商的 repeat 首延迟和间隔不同。状态机只把首次 DOWN 当成一次 tap；actor 真正确认 active press 后才创建 50 ms ticker，结束或失效立即取消，不存在常驻全局 ticker。2 秒未收到 heartbeat/UP 时 watchdog 停止连续调整；已经取消但仍等待 UP 的 owner 另有低频 drain watchdog，避免永久粘住。纯数学积分使用绝对时间，因此结果不依赖 tick 或 repeat 次数，但 OEM 是否投递稳定 KeyEvent 仍须真机验证。

## 并发与 epoch

所有生命周期、设置、手动刷新和音频环境变化都会先在调用侧把 eligibility 设为 false，再增加 `controlEpoch`；音频环境或未预告的实际路由变化还增加 `routeEpoch`。首次 DOWN 捕获两个 epoch，actor 在 snapshot、`setStreamVolume()` 和写后 snapshot 的 I/O 前后都复核 epoch 与 route ID。即使失效事件和 Binder 调用并发，旧结果也不能重新启用映射或继续排队写入。

命令队列有界；可合并的 Tick 在队列满时允许丢弃，因为 reducer 按绝对时间积分。生命周期命令和已消费手势的 UP 使用保证投递路径。coordinator 只在 actor 中修改 mapping、pending target 和 verification cycle；回调侧只拥有原子资格、owner 与 heartbeat。

## Android 17 生命周期

Android 17 对后台音频焦点、播放和系统音量修改进行了强化。后台 `setStreamVolume()` 需要可见 Activity，或满足要求的非 `shortService` 前台服务；target 37 还涉及 while-in-use 资格，违规调用可能静默无效。

本项目采用：

1. 用户在可见 Activity 中点击“启动映射”；
2. Activity 启动 `foregroundServiceType="specialUse"` 服务；
3. 服务立即显示可停止的常驻通知；
4. AccessibilityService 只有在 FGS 健康时才消费新手势；
5. 服务使用 `START_NOT_STICKY`，不在开机或无障碍回调中后台自启。

应用并不播放媒体，因此不能把 FGS 冒充 `mediaPlayback`。`specialUse` 是否获准上架仍由 Play 审核决定。

官方资料：[Android 17 后台音频强化](https://developer.android.com/about/versions/17/changes/bg-audio)、[specialUse 服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)。

## 路由与多厂商策略

核心算法不按 `Build.MANUFACTURER` 分支，也不假定媒体范围是 0–15 或 0–150。后端按音频环境 generation 缓存路由范围，并读取：

- `isVolumeFixed`；
- `getStreamMinVolume()` / `getStreamMaxVolume()`；
- 当前 index；
- API 33+ 的 `getAudioDevicesForAttributes(USAGE_MEDIA)`；
- API 28–32 的 MediaRouter/已连接输出设备启发式结果；
- 仅对 `CONFIRMED` 路由读取每个 index 的 `getStreamVolumeDb()`。

API 33+ 为媒体属性恰好返回一个设备时标记 `CONFIRMED`；返回多个设备、结果含歧义或 API 28–32 回退选择时标记 `HEURISTIC`。启发式 device type 可能生成“看似有效但属于错误设备”的 dB 表，因此此时 dB 表保持为空，DECIBELS 模式明确回退 INDEX。`stableId` 组合 route type、`AudioDeviceInfo.id` 与产品名，只用于当前运行时识别，不能作为跨重连或 OTA 的永久设备 ID。

设备、MediaRouter 或 API 31+ mode 回调会使范围缓存失效并触发重新探测。active press 还每约 500 ms 在 actor 中刷新 mode 并读取轻量缓存快照，覆盖 API 28–30 没有 mode listener、以及设备未 add/remove 却发生实际媒体路由切换的情况；该 guard 不用观察到的 current index 覆盖 active logical/expected。

## 写入、节流和故障放行

- 连续长按写入按 70 ms 节流，稳态最多约 14 次/秒；首次 DOWN 和最终 UP 的强制写入不受这条稳态节流限制；
- 每次新手势先读取真实音量，并强制 `expectedIndex = observed`；只有平台 index、路由和范围都未变化时才保留小于一个系统档位的逻辑余量；
- 第一次成功写入创建一个 verification cycle，后续连续写只更新该 cycle 的 latest expected，而不是创建会持续饿死的 per-write generation；
- cycle 约 80 ms 首次回读、约 380 ms 最终回读；若 latest write 尚不足 380 ms，松手后会延后 final，连续 active hold 则开始下一固定周期；
- control/route epoch 或 route ID 过期的验证直接丢弃；
- 任一次 mismatch（包括首次回读）都立即以系统 observed index 同步 logical state，final mismatch 才计一次失败；
- 连续三次失败进入 fail-open，后续新按键恢复系统默认行为；
- 用户可在 UI 重新探测，或从通知停止服务。

小米可能把两个系统 index 映射到相同 AVRCP 值；“系统回读一致”只能证明 AudioService 接受了请求，不能证明耳机产生了可听响度差异。

## 安全与隐私

无障碍 XML 在所有版本明确设置 `canRetrieveWindowContent=false`，API 31+ 资源另设 `isAccessibilityTool=false`，且不申请截图、手势或窗口内容能力。应用只处理音量键的键码与时间，所有曲线和诊断数据保存在本地。

Google Play 发布时需要在正常流程中显示独立显著披露、取得主动同意，并提交 Accessibility API declaration。相关政策：[Accessibility API 政策](https://support.google.com/googleplay/android-developer/answer/10964491)。
