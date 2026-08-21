# 多厂商与耳机兼容策略

## 原则

兼容层遵循“能力探测优先、厂商品牌仅做提示”：

- 不硬编码某个手机的最大音量档；
- 不访问隐藏 Settings key、隐藏广播或私有 AudioService；
- 写入后必须回读，因为某些 ROM 会静默忽略调用；
- 路由、系统版本或 ROM OTA 改变后重新探测；
- 无法确认能力时回退为标准媒体 index 曲线，并允许用户立即停止。

## 已知差异

| 平台/路由 | 可能差异 | 当前策略 |
|---|---|---|
| 小米 MIUI / HyperOS | 媒体可为 0–150；存在“调节媒体音量”权限和后台限制 | 运行时读取范围；双阶段回读；诊断页给出权限提示 |
| Samsung One UI | repeat 节奏、音量面板、SoundAssistant 或其他 key-filter 服务共存行为不同 | active-only ticker；不依赖面板；Android 只允许一个服务过滤按键，冲突时本应用可能收不到事件 |
| OPPO / OnePlus / vivo / Honor | 自启动、省电、锁屏 FGS 管理不同 | 不后台自启；用户显式启动；持续通知；真机矩阵验证 |
| Pixel / AOSP | 作为公开 API 基准 | 仓库提供 API 28/37 AVD 与测试脚本；是否通过必须按具体提交记录，不能由脚本存在推断 |
| A2DP | AVRCP 绝对音量常见范围 0–127，可有重复物理档 | 展示系统阶梯；不宣称额外物理精度 |
| LE Audio | VCS 常见范围 0–255，耳机也可自主改音量 | 重新同步系统 readback；不拦截无 KeyEvent 的远端通知 |
| USB DAC / HDMI | 设备可能固定音量或在外设端控制 | 检查 `isVolumeFixed`，固定时 fail-open |

小米官方资料：[无极音量适配](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1599)、[调节媒体音量权限](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1813)。

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
- API 28–32：MediaRouter 与连接设备只能给出启发式选择，DECIBELS 模式会因没有可信 dB 表而回退 INDEX。
- API 31–37：使用公开 mode listener 尽快得知通话/通信模式变化；API 28–30 在 active hold 中每约 500 ms 主动刷新。
- 所有版本：active hold 的同一 500 ms guard 同时核对实际 route ID、范围和 fixed-volume；环境变化会使缓存失效并增加 epoch。

这些是实现策略，不是兼容性通过记录。API 28、API 37 和各 OEM/耳机组合仍需分别执行 [TESTING.md](TESTING.md) 中的步骤。
