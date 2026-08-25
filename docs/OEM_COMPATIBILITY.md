# 多厂商与耳机兼容策略

## 原则

兼容层遵循“能力探测优先、厂商品牌仅做提示”：

- 不硬编码某个手机的最大音量档；
- 不访问隐藏 Settings key、隐藏广播或私有 AudioService；
- 写入后必须回读，因为某些 ROM 会静默忽略调用；
- 路由、系统版本或 ROM OTA 改变后重新探测；
- 无法确认路由细节时仍只按公开媒体 index 工作，并允许用户立即停止。

## 已知差异

| 平台/路由 | 可能差异 | 当前策略 |
|---|---|---|
| 小米 MIUI / HyperOS | 媒体可为 0–150；存在“调节媒体音量”权限和后台限制 | 运行时读取范围；双阶段回读；诊断页提供公开应用详情入口与保守排障提示 |
| Samsung One UI | repeat 节奏、音量面板、SoundAssistant 或其他 key-filter 服务共存行为不同 | active-only ticker；不依赖面板；系统可向多个服务并行分发，任一服务处理都可能阻止默认行为，共存顺序不能保证 |
| OPPO / OnePlus / vivo / Honor | 自启动、省电、锁屏 FGS 管理不同 | 不后台自启；用户显式启动；API 33+ 静默 FGS；真机矩阵验证 |
| Pixel / AOSP | 作为公开 API 基准 | 仓库提供 API 28/36/37 AVD 与测试脚本；是否通过必须按具体提交记录，不能由脚本存在推断 |
| A2DP | AVRCP 绝对音量常见范围 0–127，可有重复物理档 | 展示系统阶梯；不宣称额外物理精度 |
| LE Audio | VCS 常见范围 0–255，耳机也可自主改音量 | 重新同步系统 readback；不拦截无 KeyEvent 的远端通知 |
| USB DAC / HDMI | 设备可能固定音量或在外设端控制 | 检查 `isVolumeFixed`，固定时 fail-open |

小米官方资料：[无极音量适配](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1599)、[调节媒体音量权限](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1813)。

### 已验证单机样本

Xiaomi 15 / HyperOS / Android 16（API 36）的扬声器媒体范围为 `0..150`。在线性 1% 短按步长、按 index 量化配置下，实体音量键在 Activity 前台、桌面后台和亮屏锁屏时均完成 `62 → 63 → 62`；熄屏 Doze 且没有媒体播放时，按键没有触发应用或系统音量写入，音量保持 `62`。目标无障碍服务可与设备原有服务同时保持 Bound，FGS 持续通知正常。

本次连接的一副耳机在蓝牙设置中的显示名称为“Xiaomi Buds 5 Pro”，但显示名称不能作为硬件型号证据。系统实际路由为 A2DP / AAC，AVRCP 绝对音量为 `true`，Android 范围为 `0..150`。无媒体播放时，手机侧键完成 `8 → 9 → 8` 并同步 `setAvrcpVolume`；耳机自身手势绕过无障碍链路，由 `com.android.bluetooth` 直接完成 `8 → 18` / `18 → 8`；外部变到 `18` 后，手机侧键得到 `18 → 19`，说明应用按当前回读值重新同步。

真实媒体播放、播放应用位于前台而本应用 Activity 位于后台时，HyperOS 没有在 Accessibility 的约 500 ms 等待窗口内取得本应用回调，随后执行系统默认调节；旧安装包又在约 81 秒后收到迟到的 `DOWN` / `UP`，造成额外写入。进程诊断当时显示目标应用仍是 FGS、`cached=false`、`isFrozen=false`，因此不能把现象归因为已经证明的“进程冻结”。当前代码按 AOSP 等待边界拒绝接管 age 大于等于 500 ms 的初始 `DOWN`，并排空匹配手势的迟到尾事件；这是安全保护，不保证 OEM 会及时分派后续按键。

安装修复版、保持同一 A2DP 路由，并把 HyperOS 省电策略从“智能限制后台运行”改为“无限制”后，哔哩哔哩前台实际播放的单次对照在按下后约 160 ms 由应用完成 `8 → 9`，没有系统 `+10/+20`，切回映射器后也没有额外写入。该次系统日志仍出现了 HyperOS 自己的 timeout 诊断字样，但紧接着明确记录 accessibility handled，实际时序未超过应用的 500 ms 新鲜度边界。这个结果同时改变了应用版本和省电策略，样本量只有一次，因此只能证明当前组合通过，不能把改善唯一归因于“无限制”。LE Audio 仍未验证。

这些结果只代表这一台手机、当前 ROM 和本次连接设备，不能外推到其他 HyperOS 版本、其他同名耳机或其他厂商。

对小米/HyperOS，建议让用户从公开的应用详情入口自行把省电策略设为“无限制”，再复测播放应用前台、映射应用后台的实体按键。上述单机对照显示它与及时分派相关，但不能宣称它一定解决其他设备或后续所有按键的分派超时。其他厂商也应使用各自可见的后台运行/电池策略，不应从应用静默修改系统设置或依赖厂商私有 Activity。

## 推荐真机矩阵

至少覆盖：

| 维度 | 样本 |
|---|---|
| AOSP 基准 | Pixel，当前稳定 Android 与 Android 17 |
| 小米 | 一台 HyperOS 当前版本，媒体 150 档机型优先 |
| 大厂 ROM | Samsung One UI；OPPO/OnePlus 或 vivo；Honor |
| 蓝牙 | A2DP 绝对音量开启/关闭；LE Audio；不支持绝对音量的旧耳机 |
| 其他输出 | 手机扬声器、有线耳机、USB DAC，条件允许时 HDMI/Cast |

每个组合都记录 ROM build、路由名称、min/max、dB 表、重复档数量、写入延迟和拒绝率。不得仅凭品牌名称永久缓存结论。

## 路由置信度与系统版本

- API 33–37：`getAudioDevicesForAttributes(USAGE_MEDIA)` 恰好返回一个设备才视为 `CONFIRMED`，允许建立 dB 表；多个候选设备仍为 `HEURISTIC`。
- API 28–32：MediaRouter 与连接设备只能给出启发式选择，不能把该设备的 dB 表当成可靠诊断结果；曲线本身始终直接使用 index。
- API 31–37：使用公开 mode listener 尽快得知通话/通信模式变化；API 28–30 在 active hold 中每约 500 ms 主动刷新。
- 所有版本：active hold 的同一 500 ms guard 同时核对实际 route ID、范围和 fixed-volume；环境变化会使缓存失效并增加 epoch。

这些是实现策略，不是完整兼容性结论。已执行样本见 [VERIFICATION.md](VERIFICATION.md)；API 版本和每个 OEM、耳机、输出路由组合仍需分别按 [TESTING.md](TESTING.md) 执行。
