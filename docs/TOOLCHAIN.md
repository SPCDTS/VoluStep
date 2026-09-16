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

Windows 使用 `gradlew.bat`。首次构建需要下载依赖，有完整缓存后可加 `--offline`。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；未提供签名环境变量时，普通 Release 构建生成未签名 APK；正式发布使用下述签名流程。单元测试、Lint 和设备测试命令见 [测试说明](TESTING.md)。

## Windows 辅助脚本

```powershell
. .\scripts\android-env.ps1
.\scripts\build.ps1
```

`android-env.ps1` 优先使用有效的 `JAVA_HOME`、`ANDROID_HOME` / `ANDROID_SDK_ROOT`，其次选择仓库内 `.toolchains/android-studio/jbr` 与 `.toolchains/android-sdk`；Windows 找不到时再查找 `C:\Program Files\Android\Android Studio\jbr` 和 `%LOCALAPPDATA%\Android\Sdk`。脚本只修改当前 PowerShell 进程。该环境脚本与完整 E2E 也支持 CI 中的 Linux PowerShell。

`build.ps1` 执行 JVM 测试、AndroidTest 编译、Debug Lint 和打包；`-Release` 额外构建未签名 Release。`open-android-studio.ps1` 可打开项目。`.toolchains/`、`.gradle/`、`.idea/` 和 `local.properties` 均已忽略，不应提交。

## 正式签名与版本更新

首次在 Windows 上创建长期签名密钥，然后构建并验证正式包：

```powershell
.\scripts\create-release-key.ps1
.\scripts\build-release.ps1
```

创建脚本生成有效期 30 年的 RSA 3072 密钥，默认放在用户目录 `.android/volustep-signing/`，通过 Windows ACL 限制为当前用户访问。目录已存在时拒绝覆盖。**请把 `.p12` 与 `password.txt` 一起备份到加密存储**；密码不会输出到终端，不要提交或公开这些文件。自有密钥可通过四个 `VOLUSTEP_*` 环境变量直接配置 Gradle。

正式安装包为 `app/build/outputs/apk/release/app-release.apk`，启用 R8、资源压缩和正式 APK 签名，附带 `.sha256` 校验文件。脚本运行单元测试、Release Lint 和 `apksigner verify`，缺少完整密钥配置则失败。普通 CI 保留不持有密钥的未签名构建。签名构建禁用 configuration cache，避免密码进入配置缓存。

每次更新在 `app/build.gradle.kts` 同时提高 `versionCode` 并更新 `versionName`。正式版始终使用同一密钥和 applicationId；既有 `.debug` 测试版与正式版是两个应用，可以并存，设置不会自动迁移。

## GitHub Release

仓库所有者登录 GitHub CLI 后，在本机运行一次：

```powershell
.\scripts\configure-release-secrets.ps1 -Repository SPCDTS/VoluStep
```

脚本把私钥和密码加密配置为该仓库的 Actions Secrets：`VOLUSTEP_KEYSTORE_BASE64`、`VOLUSTEP_STORE_PASSWORD`、`VOLUSTEP_KEY_ALIAS`、`VOLUSTEP_KEY_PASSWORD`。只有同意将发布凭据保存在该仓库时才执行。

推送与 `versionName` 一致的 `v版本号` 标签后，[发布工作流](../.github/workflows/release.yml) 先执行完整 CI 和模拟器测试，再签名并验证 APK，生成带 SHA-256 的 **Release 草稿**。检查草稿中的正式包和说明后发布。已有同名 Release 时工作流失败，不覆盖已发布安装包。混淆映射单独作为 Actions 构建产物保留，供崩溃分析使用。

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
