# 离散音量曲线设计

## 产品语义

曲线直接描述“第几次按键之后，Android 媒体音量应落在哪个整数 index”，不再先产生连续音量再做二次量化。

设当前可编辑基准范围为 `minIndex..maxIndex`，跨度 `S = maxIndex - minIndex`，用户配置从最小到最大需要 `K` 次短按：

- 横轴有 `K+1` 个状态点，位置固定为 `xᵢ = i / K`，用户不能横向移动；
- 纵轴保存整数 offset `oᵢ`，实际目标是 `minIndex + oᵢ`；
- 两个端点固定为 `o₀ = 0`、`oₖ = S`；
- 中间点满足 `0 < o₁ < … < oₖ₋₁ < S`，因此每次短按至少改变一个 Android index；
- 相邻差值 `Δᵢ = oᵢ - oᵢ₋₁` 表示这一按跨越多少个系统 index，也是判断低音量区域是否足够细的主要指标。

严格递增意味着 `1 ≤ K ≤ S`。如果设置来自较大范围，而当前路由只有更小的跨度，运行时采用 `K_effective = min(K, S)`，重新投影出严格递增的临时表，并在界面明确提示；不会偷偷制造“按了但 index 不变”的重复点。

当系统或耳机把音量外部改到两个配置点之间时，音量加选择第一个严格大于当前 index 的点，音量减选择最后一个严格小于当前 index 的点。这样不会先跳回旧状态，也不会依赖浮点反解的舍入方向。

## 编辑交互

编辑器分成三层，分别覆盖整体结构、快速塑形和精确输入：

1. 顶部设置 `K`，提供减一、整数输入、加一和常用次数快捷项。修改次数后保持原曲线外形，再把采样结果投影到合法整数序列。
2. 主画布固定均匀显示所有状态点。点击选择一点；纵向拖动修改 index，横向经过其他点时形成画笔式连续编辑。拖过相邻点时采用最小推挤，而不是把当前点卡在邻点前一格，因此密集曲线仍然可画。
3. 选中点面板显示 `第 i/K 步`、精确 index、前后 `Δindex`，并提供 `-1`、整数输入和 `+1`。端点可选中查看，但不可改写。

辅助能力包括：

- 每步 `Δindex` 条带，快速发现突跳或过密区域；
- 当前系统音量位置叠加在曲线上，但不改变草稿；
- 手势开始时记录一个历史快照，整次拖动只产生一条撤销记录；
- 撤销、重做、线性重置和预设；历史栈有上限，避免长期编辑无限增长；
- 拖动过程中只更新界面草稿，手势结束或精确输入确认后一次性提交 DataStore；
- 画布之外提供标准按钮和文本输入语义，确保 TalkBack、键盘与自动化测试不依赖自定义手势。

## 点数变化与跨设备投影

修改 `K` 时，先在新的均匀横坐标上对旧折线做线性采样，再求满足固定端点和严格递增约束的整数序列。实现使用确定性的最小平方动态规划，复杂度为 `O(K × S)`；在 Android 常见的几十到一百多个 index 范围内成本很低，并且比逐点四舍五入更能保持整体形状。

切换到不同厂商或不同音频路由时使用同一过程把基准 offset 缩放到实际跨度。设置保存的是完整基准曲线，较小路由上的临时降采样不会破坏用户为较大范围调好的细节；用户在该路由上实际编辑后，才把它重设为新的编辑基准。

## 开源交互调研

没有找到一个 Android/Compose 开源控件同时提供“固定均匀横坐标、整数纵坐标、严格单调、推挤编辑、按键次数语义”。直接引入通用图表库仍需自行实现命中测试、手势事务和约束求解，所以本项目使用 Compose `Canvas` 与标准 Material 控件组合，不增加图表依赖。

可复用的是以下成熟产品模式，而不是直接复制代码：

| 项目 | 可借鉴交互 | 采用方式与许可证边界 |
|---|---|---|
| [Godot CurveEdit](https://github.com/godotengine/godot/blob/master/editor/plugins/curve_editor_plugin.cpp) | 点拖动、吸附、轴锁定、预设、按一次手势记录撤销 | MIT；借鉴手势事务和约束反馈 |
| [AOSP Camera2 ImageCurves](https://android.googlesource.com/platform/packages/apps/Camera2/+/394baebc5579fa9c3f7e09bfbcac7ef666f6c002/src/com/android/gallery3d/filtershow/imageshow/ImageCurves.java) | 移动端触摸命中、拖点与相邻点边界 | Apache-2.0；借鉴移动端直接操控方式 |
| [Audacity Envelope Tool](https://manual.audacityteam.org/man/envelope_tool.html) | 包络线点编辑、视觉反馈 | 文档用于交互参考；GPL 实现不进入本项目 |
| [Ardour Automation](https://manual.ardour.org/mixing/automation/controlling-a-track-with-automation/) | 画笔式自动化、范围选择与精调 | 文档用于交互参考；GPL 实现不进入本项目 |
| [Vico](https://github.com/patrykandpatrick/vico) / [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) | Android 图表绘制、缩放和标记 | 都擅长展示，但没有本项目需要的受约束点编辑；不引入 |
| [32steps](https://github.com/nulldio/32steps) | 离散步数与响度细分的 Android 实验 | 其额外精度依赖播放 session 上的 DSP 增益，不等同于全局 `STREAM_MUSIC` index 曲线；仅作边界对照 |

`32steps` 一类方案可以通过 [`DynamicsProcessing`](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing) 在自己掌控的 `AudioTrack`/`MediaPlayer` session 内增加 index 之间的增益级，但本应用控制的是其他应用共同使用的全局媒体音量。把 session DSP 当作全局保证会受到播放器、音效链和厂商实现限制，因此当前版本明确把纵轴保持为系统 audio index。

## 可验证不变量

- `offsets.size == K + 1`；
- 横坐标始终均匀，不持久化浮点 `x`；
- 端点固定、所有 offset 为整数且严格递增；
- 同一路由上的短按只移动到相邻配置状态，外部 index 则按方向选择严格相邻目标；
- 点数变化和跨范围投影结果确定、可重复；
- 固定音量路由没有可用步骤，控制器保持 fail-open；
- Android index 写入回读成功只证明系统接受该 index，不保证蓝牙耳机固件产生可听差异。
