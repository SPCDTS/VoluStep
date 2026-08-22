# 本机 Android 工具链记录

本项目使用项目隔离工具链，安装目录为 `.toolchains/`，已被 Git 忽略。`scripts/android-env.ps1` 只修改当前 PowerShell 进程的环境变量，不永久覆盖系统的 `JAVA_HOME`、SDK 或 `PATH`。

## 已安装组件

| 组件 | 本机版本/包 |
|---|---|
| Android Studio | 2026.1.3 Patch 1，build `AI-261.26222.65.2613.16025427` |
| Studio Runtime | OpenJDK/JBR `25.0.2` |
| Android CLI | `1.0.15985488`；项目 SDK launcher `1.0.15498356`；全局 launcher `1.0.15985488` |
| Command-line Tools | `22.0` |
| Platform Tools | `37.0.1` |
| Emulator | `37.1.11.0` |
| Platforms | `android-37.0` |
| Build Tools | `36.0.0`、`37.0.0` |
| System Images | Android 9/API 28 Google APIs x86_64；Android 16/API 36 Google APIs x86_64；Android 16/API 36.1 Google Play x86_64；Android 17/API 37 Google APIs x86_64 |

下载归档保存在 `.toolchains/downloads/`，用于离线修复当前环境：

| 文件 | SHA-256 |
|---|---|
| `android-studio-quail3-patch1-windows.zip` | `758E927767972C44F2BB14E0AF035E6B90EC07A9E2819FC2EB83F51A2492501B` |
| `commandlinetools-win-15859902_latest.zip` | `90AE805D20434428BFFCB699C290860F19BB5F66A67E6B330067E3DE801FB04A` |

## 已创建模拟器

- `VolumeMapper_API_28`：Pixel 2，Android 9/API 28；
- `VolumeMapper_API_36`：Pixel 7，Android 16/API 36；
- `VolumeMapper_API_37`：Pixel 7，Android 17/API 37。

AVD 数据由 Android 保存在当前用户的 `.android/avd/`，不属于仓库。重复运行 `scripts/create-avds.ps1` 会保留已有设备；传入 `-Force` 才会重建项目命名的三个 AVD。

AVD 已创建、系统镜像已安装或 smoke 脚本可启动，只说明测试环境可用，不等于对应 API 的 instrumentation、音频行为或 Android 17 hardening 验收已经通过；测试结果应另按提交和日期记录。

## Android CLI 与官方 skills

项目 SDK 已包含 `android` CLI，`scripts/android-env.ps1` 会优先使用这一份；Google 官方 Windows 安装器另将全局 launcher 安装到 `%USERPROFILE%\AppData\AndroidCLI` 并加入当前用户 `PATH`，便于在项目外直接调用。新终端或重启宿主应用后才会读取更新后的用户 `PATH`。

当前用户的 Codex 环境已安装以下 Google/Android 官方 skills，来源为 `android/skills`：

- `android-cli`
- `testing-setup`
- `edge-to-edge`
- `android-profiler`
- `r8-analyzer`
- `play-policy-insights`
- `android-intent-security`

没有使用 `--all`：Camera、Wear OS、XR、Billing 和迁移类 skills 与当前应用无关。skills 在新的 Codex 任务轮次中生效。

Android CLI 当前仍为 Preview。官方已注明 Windows 下 `android emulator` 子命令暂不可用，因此本项目继续通过 `scripts/start-emulator.ps1` 调用 SDK 自带的 `emulator.exe`。传统 `sdkmanager` 仍保留，但新脚本应优先考虑 `android sdk`。

## 常用入口

```powershell
. .\scripts\android-env.ps1
.\scripts\open-android-studio.ps1
.\scripts\build.ps1 -Release
.\scripts\start-emulator.ps1 -Api 37
```

生产签名库不在工具链目录中，也不会自动生成。发布者应按 `docs/RELEASE.md` 创建、备份并以进程环境变量提供签名材料。
