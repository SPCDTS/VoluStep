# 开发环境

## 环境要求

| 组件 | 配置 |
|---|---|
| JDK | CI 使用 17，源码与字节码目标为 Java 17 |
| Android SDK | Platform 37、Build Tools 37.0.0、Platform Tools |
| Gradle | 使用仓库内 Wrapper，无需全局安装 |
| Android Studio | 可选；Windows 辅助脚本会使用其附带的 JBR |

依赖版本见 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)、[`app/build.gradle.kts`](../app/build.gradle.kts) 和 [`gradle-wrapper.properties`](../gradle/wrapper/gradle-wrapper.properties)。

## 命令行构建

设置 `JAVA_HOME` 与 `ANDROID_HOME`，或在未跟踪的 `local.properties` 中指定 SDK：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

```bash
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat`。首次构建需要下载依赖，有完整缓存后可加 `--offline`。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；Release 构建未配置签名。单元测试、Lint 和设备测试命令见 [测试说明](TESTING.md)。

## Windows 辅助脚本

```powershell
. .\scripts\android-env.ps1
.\scripts\build.ps1
```

`android-env.ps1` 优先选择仓库内 `.toolchains/android-studio/jbr` 与 `.toolchains/android-sdk`；找不到时，分别查找 `C:\Program Files\Android\Android Studio\jbr` 和 `%LOCALAPPDATA%\Android\Sdk`。脚本只修改当前 PowerShell 进程。若使用其他安装位置，请直接配置环境变量后调用 Gradle Wrapper。

`build.ps1` 执行 JVM 测试、AndroidTest 编译、Debug Lint 和打包；`-Release` 额外构建未签名 Release。`open-android-studio.ps1` 可打开项目。`.toolchains/`、`.gradle/`、`.idea/` 和 `local.properties` 均已忽略，不应提交。

## 模拟器

创建 AVD 前安装 Emulator、Command-line Tools 及所需 Google APIs x86_64 镜像。脚本使用的镜像包为 `system-images;android-28;google_apis;x86_64`、`system-images;android-36;google_apis;x86_64` 和 `system-images;android-37.0;google_apis;x86_64`。

```powershell
.\scripts\create-avds.ps1
.\scripts\start-emulator.ps1 -Api 37
```

创建脚本需要上述三个镜像；已有其他 AVD 时可直接使用 Android Studio 或 Emulator 启动。自动测试的设备选择和数据清理行为见 [测试说明](TESTING.md)。

## 常见问题

- `SDK location not found`：检查 SDK 环境变量或 `local.properties`。
- `Unsupported class file`：用 `gradlew.bat --version` 检查 Gradle 实际使用的 JDK，并对照 CI 配置。
- 平台或工具缺失：通过 SDK Manager 安装对应包，确认安装目录与构建使用的 SDK 一致。
