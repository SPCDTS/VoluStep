# VoluStep

VoluStep 是一个面向 Android 28–37 的媒体音量控制应用。它既能用无障碍服务把手机实体音量键的状态 `x(t)` 映射到可编辑曲线，也能从主界面的自定义固定值按钮直接设置媒体音量；两条路径最终都只使用公开 `AudioManager` API 和当前路由实际支持的整数音量档位。

项目当前实现：

- `K` 次短按与 `P` 个自由控制点独立配置；`K+1` 个按键位置始终均匀采样，控制点可在横轴和整数 audio index 纵轴上分别吸附；选中线段可在该段插入，选中控制点则在其右侧线段插入（末点改用左侧），选中内部点后可精确删除；
- 正式版只有一个简约主界面：直接拖动折线、输入 `K`、通过当前点/线段调整 `P`、查看当前音量水平标记、管理固定音量按钮，并设置固定长按步进间隔；
- 控制点横坐标与整数 index 一并持久化，修改 `K` 不改变折线，修改 `P` 也不会把已有控制点重新均匀排布；
- 曲线按当前路由实际 min/max 投影；较小范围只临时减少有效按键次数，不覆盖完整 K/P 作者配置；
- 固定音量按钮保存实际 media index，可连续添加、删除和横向滚动；点击按钮不依赖总开关、无障碍或映射前台服务，当前值仅以绿色荧光描边标示，换路由后的越界值保留但禁用；
- AccessibilityService 全局过滤音量键，以 `(deviceId, keyCode, downTime)` 标识一次手势；repeat 只刷新心跳，固定 300 ms 阈值触发首个连续步进，active press 使用 20 ms、latest-only ticker；
- 无障碍连接期间持有一个透明、不可交互的 1×1 `TYPE_ACCESSIBILITY_OVERLAY` 运行锚点；主界面任务始终不进入最近任务，需从桌面图标重新打开；该策略不申请悬浮窗权限；
- Android 17 所需、由可见 Activity 显式启动的 `specialUse` 前台服务；
- 音量写入后的单周期两阶段回读、control/route epoch 失效、路由切换重置和连续失败自动放行；
- A2DP、LE Audio、USB、HDMI、有线与扬声器的运行时能力探测，并区分 `CONFIRMED` 与 `HEURISTIC` 路由；
- 显著披露、应用内停止开关，以及收纳在“设备”折叠区中的跨 OEM 状态与公开应用详情入口；API 33+ 不声明通知权限，FGS 只在系统“运行中的应用”入口留有状态；
- 英文默认资源与简体中文 `zh-CN` 资源；品牌名 VoluStep 在两种语言中保持一致，界面、无障碍语义、运行状态和系统通知随应用语言切换；
- 18 条 JVM 单元测试、12 条 Android instrumentation / E2E 测试、API 36/37 模拟器完整 E2E、API 28 最低版本安装/启动烟测、真机诊断采集和 Release/AAB 流程。

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

默认脚本运行 20 条 JVM 单测、编译 10 条 instrumentation / E2E 测试、执行 Debug lint 并构建 Debug APK；只有传入 `-Release` 才会额外构建未签名门禁用 Release APK/AAB。

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
.\scripts\emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild -AppLocale en-US
.\scripts\emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild -AppLocale zh-CN
```

完整 E2E 脚本只允许 `emulator-*`：它会清空测试应用数据、按 `-AppLocale` 选择英文或简体中文、临时修改 secure accessibility settings、把可调试模拟器的 adbd 切到 root，并从 evdev 注入真正经过 Accessibility input filter 的音量键事件；结束时会恢复原无障碍配置、媒体/铃声音量和 adbd 身份。仓库保持 18 条 JVM + 12 条 instrumentation / E2E，共 30 个唯一测试入口；固定音量编辑和真实 `AudioManager` 写入分别由独立测试覆盖，语言矩阵复用同一测试与 E2E 旅程，不靠复制 `@Test` 增加条数。仓库中存在 AVD、脚本或测试源码，不代表任何 API 或真机矩阵已经执行通过；执行范围与记录规则见 [docs/TESTING.md](docs/TESTING.md)，本次实际执行结果见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 小米真机使用

1. 在开发者选项开启 USB 调试，连接后运行 `adb devices -l`。
2. 安装 Debug APK，打开应用并阅读、勾选显著披露。
3. 进入“无障碍设置”，启用“VoluStep 音量控制”。
4. 回到应用，打开顶部 VoluStep 主开关；Android 17 必须由这个可见界面启动前台服务。
5. 启动后按 Home 即可；VoluStep 不会出现在最近任务中，需从桌面图标重新打开并从主开关停止。
6. 在主界面的“设备”折叠区确认蓝牙路由、系统 min/max 和运行状态。
7. HyperOS 若连续回读失败，检查“调节媒体音量”权限、后台运行和省电限制。

真机测试流程见 [docs/TESTING.md](docs/TESTING.md)，离散曲线与开源交互调研见 [docs/CURVE_EDITOR.md](docs/CURVE_EDITOR.md)，架构与厂商适配见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 和 [docs/OEM_COMPATIBILITY.md](docs/OEM_COMPATIBILITY.md)。

当前单机样本已在 Xiaomi 15 / HyperOS / Android 16（API 36）验证扬声器前台、桌面后台、亮屏锁屏和 A2DP 绝对音量的精细步进。连接耳机的蓝牙显示名称为“Xiaomi Buds 5 Pro”，但这不能证明其具体硬件型号。真实媒体播放时曾发现 HyperOS 让后台无障碍按键回调超过系统 500 ms 窗口并延迟送达；当前代码已增加过期事件放行与手势排空保护。把该应用的 HyperOS 省电策略从“智能限制”改为“无限制”后，同场景单次对照在约 160 ms 内完成 `8 → 9`，且回前台没有迟到写入；这是一个样本的排障结果，不是跨设备保证。LE Audio 尚未验证。完整证据和边界见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 能力边界

本项目首版的准确产品定义是“全局媒体音量键映射”，不是系统主增益替代品：

- 普通应用最终只能写公开的整数 `STREAM_MUSIC` index，不能改 AudioPolicy 音量曲线、post-mix 增益或蓝牙原始 AVRCP/VCS 值；
- `getStreamVolumeDb()` 是 Android 策略衰减，不是耳机真实声压 SPL；
- 当前映射纵轴只使用实际整数 index；dB 表保留用于诊断，不参与按键目标计算；
- A2DP 绝对音量通常只有 0–127，LE Audio VCS 通常只有 0–255，耳机固件还可能合并相邻档位；
- 耳机自身按键若直接发绝对音量通知，可能完全不经过 Accessibility `KeyEvent`；
- 检测到系统通话音频模式时会放行；公开 API 无法可靠识别所有闹钟、相机和 OEM 前台场景，使用这些功能前应从应用主开关停止映射；
- 消费实体音量键可能影响截图或厂商组合键；应用主开关和系统“运行中的应用”入口都可立即停止控制器；
- 系统可同时把按键分发给多个请求过滤的无障碍服务；任一服务处理事件都可能阻止默认系统行为，共存结果与顺序不能保证，本应用也没有更高优先级。
- 1×1 无障碍窗口只是针对部分 OEM 后台策略的实验性运行锚点；它不能恢复被撤销的授权、抵抗系统或用户的强制停止，也不保证熄屏后仍投递按键。

应用不使用隐藏 API、不反射 AudioService、不绕过安全音量提示，也不会读取屏幕内容。

## 发布

签名材料绝不能提交。普通 `build.ps1 -Release` 只用于本地门禁，不会产出可直接发布的正式包。正式构建前先运行 `scripts/install-bundletool.ps1`；随后使用 `scripts/build-release.ps1 -SigningProfile AppSigning` 构建直接分发 APK，或使用 `-SigningProfile PlayUpload` 构建 Google Play AAB。两种模式都强制传入离线记录的证书 SHA-256 和最终公开的 HTTPS 隐私政策地址，完整命令见发布文档。

完整签名、R8、APK/AAB 和 Play 声明流程见 [docs/RELEASE.md](docs/RELEASE.md)，可复核的 Console 填写草稿见 [docs/PLAY_CONSOLE_DECLARATIONS.md](docs/PLAY_CONSOLE_DECLARATIONS.md)，逐项阻断条件见 [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)，商店图文素材位于 [distribution/store-assets](distribution/store-assets)，隐私说明草案见 [docs/PRIVACY.md](docs/PRIVACY.md)。发布前必须补充真实发布主体、联系邮箱和最终 HTTPS 隐私政策地址，并完成 Accessibility API 与 `specialUse` FGS 两套 Play Console 声明和演示视频。

当前 1×1 运行锚点与始终隐藏最近任务的组合优先面向直接分发验证。若重新选择 Google Play，应在提交前单独复核 Accessibility API 政策与审核披露，不能把本地可用等同于商店合规。
