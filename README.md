# 音量映射器

这是一个面向 Android 28–37 的全局媒体音量键映射应用。它用无障碍服务接收手机实体音量键，把按键状态 `x(t)` 交给可编辑曲线，再通过公开 `AudioManager` API 写入当前媒体路由实际支持的整数音量档位。

项目当前实现：

- 可拖拽、可用 Slider 精调的 `x → V` 单调分段线性曲线；
- 线性、低音量精细、S 曲线、夜间上限四种预设；
- 可调短按步长、长按延迟、长按速度与加速曲线；
- INDEX 与 dB 两种量化方式，并叠加显示当前设备的实际阶梯；
- AccessibilityService 全局过滤音量键，以 `(deviceId, keyCode, downTime)` 标识一次手势；repeat 只刷新心跳，长按才启动 50 ms ticker；
- Android 17 所需、由可见 Activity 显式启动的 `specialUse` 前台服务；
- 音量写入后的单周期两阶段回读、control/route epoch 失效、路由切换重置和连续失败自动放行；
- A2DP、LE Audio、USB、HDMI、有线与扬声器的运行时能力探测，并区分 `CONFIRMED` 与 `HEURISTIC` 路由；
- 显著披露、持续通知停止开关、跨 OEM 后台/省电诊断与公开应用详情入口；
- JVM 单元测试、Android instrumentation 测试、API 28/36/37 模拟器 E2E、真机诊断采集和 Release/AAB 流程。

## 本机环境

项目使用以下固定基线：

| 组件 | 版本 |
|---|---:|
| Android Studio | Quail 3 / 2026.1.3 Patch 1 |
| JDK | Studio 内置 JBR 25.0.2；源码目标 Java 17 |
| Android Gradle Plugin | 9.3.1 |
| Gradle Wrapper | 9.5.0，启用 SHA-256 校验 |
| compileSdk / targetSdk | 37 / 37 |
| minSdk | 28 |
| Compose BOM | 2026.08.00 |

工具链被安装在项目忽略目录 `.toolchains/`，不会进入 Git；完整版本、归档校验值和 AVD 记录见 [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md)。每个 PowerShell 会话先执行：

```powershell
. .\scripts\android-env.ps1
```

脚本会优先使用项目内的新版 Studio JBR 和 SDK；若不存在，才回退到系统已安装版本，不会要求全局 Gradle。

直接用已配置好的 Android Studio 打开项目：

```powershell
.\scripts\open-android-studio.ps1
```

## 构建与测试

```powershell
.\scripts\build.ps1
```

默认脚本运行 JVM 单测、Debug lint 与 Debug APK；只有传入 `-Release` 才会额外构建 Release APK/AAB。

产物位置：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`app/build/outputs/apk/release/`
- Release AAB：`app/build/outputs/bundle/release/app-release.aab`
- Lint：`app/build/reports/lint-results-*.html`
- 单元测试：`app/build/reports/tests/testDebugUnitTest/`

创建三个隔离 AVD：

```powershell
.\scripts\create-avds.ps1
.\scripts\start-emulator.ps1 -Api 37
```

模拟器启动后：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
.\scripts\emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild
```

完整 E2E 脚本只允许 `emulator-*`：它会清空测试应用数据、临时修改 secure accessibility settings、把可调试模拟器的 adbd 切到 root，并从 evdev 注入真正经过 Accessibility input filter 的音量键事件；结束时会恢复原无障碍配置、媒体/铃声音量和 adbd 身份。仓库中存在 AVD、脚本或测试源码，不代表任何 API 或真机矩阵已经执行通过；执行范围与记录规则见 [docs/TESTING.md](docs/TESTING.md)，本次实际执行结果见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 小米真机使用

1. 在开发者选项开启 USB 调试，连接后运行 `adb devices -l`。
2. 安装 Debug APK，打开应用并阅读、勾选显著披露。
3. 进入“无障碍设置”，启用“音量键映射服务”。
4. 回到应用，点击“启动映射”；Android 17 必须由这个可见界面启动前台服务。
5. 在诊断页确认蓝牙路由、系统 min/max、dB 样本和回读状态。
6. HyperOS 若连续回读失败，检查“调节媒体音量”权限、后台运行和省电限制。

真机测试流程见 [docs/TESTING.md](docs/TESTING.md)，架构与厂商适配见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 和 [docs/OEM_COMPATIBILITY.md](docs/OEM_COMPATIBILITY.md)。

当前单机样本已在 Xiaomi 15 / HyperOS / Android 16（API 36）验证扬声器前台、桌面后台、亮屏锁屏和 A2DP 绝对音量的精细步进。连接耳机的蓝牙显示名称为“Xiaomi Buds 5 Pro”，但这不能证明其具体硬件型号。真实媒体播放时曾发现 HyperOS 让后台无障碍按键回调超过系统 500 ms 窗口并延迟送达；当前代码已增加过期事件放行与手势排空保护。把该应用的 HyperOS 省电策略从“智能限制”改为“无限制”后，同场景单次对照在约 160 ms 内完成 `8 → 9`，且回前台没有迟到写入；这是一个样本的排障结果，不是跨设备保证。LE Audio 尚未验证。完整证据和边界见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 能力边界

本项目首版的准确产品定义是“全局媒体音量键映射”，不是系统主增益替代品：

- 普通应用最终只能写公开的整数 `STREAM_MUSIC` index，不能改 AudioPolicy 音量曲线、post-mix 增益或蓝牙原始 AVRCP/VCS 值；
- `getStreamVolumeDb()` 是 Android 策略衰减，不是耳机真实声压 SPL；
- API 33+ 只有系统为媒体属性明确返回单一路由时才使用 dB 表；多路由歧义或 API 28–32 的启发式路由会回退到 index 量化；
- A2DP 绝对音量通常只有 0–127，LE Audio VCS 通常只有 0–255，耳机固件还可能合并相邻档位；
- 耳机自身按键若直接发绝对音量通知，可能完全不经过 Accessibility `KeyEvent`；
- 检测到系统通话音频模式时会放行；公开 API 无法可靠识别所有闹钟、相机和 OEM 前台场景，使用这些功能前应从通知或应用内停止映射；
- 消费实体音量键可能影响截图或厂商组合键，持续通知中的“停止映射”是立即恢复开关；
- 系统可同时把按键分发给多个请求过滤的无障碍服务；任一服务处理事件都可能阻止默认系统行为，共存结果与顺序不能保证，本应用也没有更高优先级。

应用不使用隐藏 API、不反射 AudioService、不绕过安全音量提示，也不会读取屏幕内容。

## 发布

签名材料绝不能提交。完整签名、R8、APK/AAB 和 Play 声明流程见 [docs/RELEASE.md](docs/RELEASE.md)，隐私说明草案见 [docs/PRIVACY.md](docs/PRIVACY.md)。发布前必须补充发布主体/联系邮箱，并完成 Accessibility API 与 `specialUse` FGS 两套 Play Console 声明和演示视频。
