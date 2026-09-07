# VoluStep

VoluStep 是一个本地优先的 Android 媒体音量控制器。它把实体音量键映射到用户可编辑的离散曲线，也允许在主界面创建固定音量按钮。

> Android 公开 API 只能写入当前媒体路由支持的整数 `index`。VoluStep 可以重新设计“按几次键到达哪个档位”，但不能生成系统或耳机本身不存在的音量档位，也不能控制真实声压级。

## 功能

- 用可拖动折线定义按键次数到媒体音量 `index` 的映射；控制点的 X/Y 坐标均吸附到整数网格。
- 按键次数和控制点数量独立配置；可选择线段插点，或删除选中的内部控制点。
- 短按移动一个映射状态；长按使用固定起效阈值和可调步进间隔，不依赖不同厂商的按键重复频率。
- 保存自定义固定音量按钮；该功能在可见界面中使用公开 `AudioManager`，无需无障碍服务。
- 针对 A2DP、LE Audio、USB、HDMI、有线和扬声器动态读取当前路由范围，不假定设备固定为 15 档。
- 默认英文与简体中文界面，单页主界面，无账号、广告、分析 SDK 或联网权限。

## 工作方式

实体音量键方案需要用户主动启用无障碍服务。服务只过滤音量加减键，不读取窗口内容、文字、触摸或截图；按键事件只在内存中用于判断短按/长按，不会持久化或上传。

```text
实体音量键
  → AccessibilityService 过滤音量键
  → 离散短按/长按状态机
  → 用户曲线投影到当前路由整数 index
  → AudioManager.setStreamVolume(STREAM_MUSIC)
  → AudioPolicy / 蓝牙协议 / 耳机固件
```

Android 17 的后台音量修改要求由可见界面启动符合条件的前台服务。VoluStep 使用 `specialUse` 前台服务维持用户显式开启的全局映射，并提供主开关和系统入口用于停止。部分 OEM 上使用的 1×1 无障碍窗口只是实验性运行锚点，不能恢复被撤销的授权、绕过强制停止或保证熄屏后仍收到按键。

详细设计见 [架构说明](docs/ARCHITECTURE.md) 与 [曲线编辑器](docs/CURVE_EDITOR.md)。

## 环境与构建

最低要求：

- JDK 17；
- Android SDK Platform 37 与 Build Tools 37；
- Android Studio 可选，项目使用仓库内 Gradle Wrapper。

Windows 可使用项目脚本配置隔离工具链并执行默认门禁：

```powershell
. .\scripts\android-env.ps1
.\scripts\build.ps1
```

macOS、Linux 或已配置好 SDK 的环境可直接运行：

```bash
./gradlew :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  :app:lintDebug \
  :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。完整环境说明见 [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md)。

## 使用

1. 安装 APK，打开 VoluStep 并阅读显著披露。
2. 进入系统无障碍设置，启用“VoluStep 音量控制”。
3. 回到应用，从顶部开关启动映射。
4. 在曲线上调整控制点和按键次数，或添加固定音量按钮。
5. 需要停止时关闭主开关、在系统“运行中的应用”入口停止，或撤销无障碍授权。

不同手机和耳机的音量范围、后台策略、绝对音量协议与固件合档行为可能不同。已知边界与厂商适配建议见 [docs/OEM_COMPATIBILITY.md](docs/OEM_COMPATIBILITY.md)。

## 测试

仓库保持 30 个独立测试入口：18 个 JVM 测试与 12 个 Android instrumentation / E2E 测试。测试入口存在不代表所有设备矩阵已经执行；当前可复核结果记录在 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

```powershell
# JVM、AndroidTest 编译、Lint、Debug APK
.\scripts\build.ps1

# 已启动模拟器上的设备测试
.\gradlew.bat :app:connectedDebugAndroidTest

# 只允许 emulator-*，会临时修改并在 finally 中恢复模拟器状态
.\scripts\emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild -AppLocale zh-CN
```

测试范围、真机注意事项和结果记录规则见 [docs/TESTING.md](docs/TESTING.md)。

## 目录

| 路径 | 职责 |
|---|---|
| `app/src/main/java/.../core` | 纯 Kotlin 曲线、整数投影与按键状态机 |
| `app/src/main/java/.../audio` | 公开 `AudioManager` 路由探测与音量读写 |
| `app/src/main/java/.../runtime` | 无障碍服务、前台服务与串行协调器 |
| `app/src/main/java/.../data` | DataStore 设置与迁移 |
| `app/src/main/java/.../ui` | Jetpack Compose 单页界面与曲线编辑器 |
| `scripts` | Windows 构建、模拟器与 E2E 脚本 |
| `docs` | 架构、测试、隐私与 OEM 兼容文档 |

## 隐私与安全

- Manifest 不声明 `INTERNET`、存储、账号、蓝牙扫描或通知权限。
- Launcher Activity 不处理外部参数；映射服务不导出；无障碍服务由系统级 `BIND_ACCESSIBILITY_SERVICE` 保护。
- 所有 `PendingIntent` 都是显式目标且不可变。
- 设置保存在应用私有 DataStore，并从云备份与设备迁移中排除，避免在新设备恢复旧的披露同意状态。

隐私说明见 [docs/PRIVACY.md](docs/PRIVACY.md)。报告问题前请主动移除配对码、设备序列号、局域网地址、账号、本机路径和未修复漏洞的可利用细节。

## 贡献

签名材料与本机诊断输出均不得提交；本仓库不包含正式签名与发布流程。

## 许可证

项目代码和仓库内自制素材以 [MIT License](LICENSE) 开源；第三方依赖仍适用各自许可证。
