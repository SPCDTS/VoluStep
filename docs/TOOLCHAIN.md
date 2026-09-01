# 开发环境

本文描述可复现的最低开发环境，不记录某一台电脑的安装目录、下载缓存或已创建 AVD。依赖版本的唯一来源是 `gradle/libs.versions.toml`、`app/build.gradle.kts` 与 Gradle Wrapper 配置。

## 必需组件

| 组件 | 要求 |
|---|---|
| JDK | 17 或更高；源码与字节码目标为 Java 17 |
| Android SDK Platform | API 37 |
| Android Build Tools | 37.x |
| Platform Tools | 与目标设备兼容的当前稳定版本 |
| Gradle | 使用仓库内 Wrapper，无需全局安装 |
| Android Studio | 可选；支持当前 AGP 与 Kotlin 版本即可 |

项目当前的 AGP、Kotlin、Compose 和 AndroidX 版本集中维护在 `gradle/libs.versions.toml`。Gradle Wrapper 归档启用了 SHA-256 校验。

## 通用环境

安装 JDK 与 Android SDK 后，确保 `JAVA_HOME` 和 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）指向有效目录。也可以在不提交的 `local.properties` 中配置：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

验证基础构建：

```bash
./gradlew :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  :app:lintDebug \
  :app:assembleDebug
```

Windows 使用 `gradlew.bat`。首次构建需要访问 Google Maven、Maven Central 和 Gradle 分发站点；之后可使用 Gradle 缓存离线构建。

## Windows 隔离工具链

仓库附带的 PowerShell 脚本优先使用被 Git 忽略的 `.toolchains/`，找不到时再回退到系统环境：

```powershell
. .\scripts\android-env.ps1
.\scripts\build.ps1
.\scripts\open-android-studio.ps1
```

这些脚本只修改当前 PowerShell 进程，不永久覆盖系统环境变量。`.toolchains/`、`.gradle/`、`.idea/` 与 `local.properties` 均不得提交。

## 模拟器

Windows 脚本可创建项目使用的 API 28、36 和 37 AVD：

```powershell
.\scripts\create-avds.ps1
.\scripts\start-emulator.ps1 -Api 37
```

AVD 位于 Android 用户目录而非仓库。创建成功只说明环境可用，不代表设备测试已经通过；结果必须按 [TESTING.md](TESTING.md) 的规则记录。

## Android CLI

Google Android CLI 可用于 SDK 管理、安装、截图和布局检查，但不是构建项目的硬依赖。已有 CLI 的开发者可运行：

```powershell
android info
android describe --project_dir .
```

真机上执行布局检查可能触发部分 OEM 重新绑定无障碍服务。VoluStep 的小米真机验收优先使用截图和只读 `adb` 查询，不在启用映射时运行 UI Automator 布局抓取。

## 常见问题

- `SDK location not found`：设置 SDK 环境变量或创建未跟踪的 `local.properties`。
- `Unsupported class file`：确认 Gradle 实际使用 JDK 17 或更高。
- API 37 资源或平台缺失：通过 Android SDK Manager 安装 Platform 37 与对应 Build Tools。
- 多设备导致测试跑错目标：设置 `ANDROID_SERIAL`，或在命令中显式指定设备。
- Release 没有签名：这是默认安全行为；正式构建必须走 [RELEASE.md](RELEASE.md) 的签名门禁。
