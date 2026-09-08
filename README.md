# VoluStep

简体中文 | [English](README-en.md)

**音量键调节幅度那么大，对于戴耳机睡觉的人来说简直就是折磨**

VoluStep 让你自定义 Android 手机实体音量键的调节方式。通过自定义曲线改变不同音量区间的按键敏感度。

<p align="center">
  <img src="docs/assets/volustep-demo.gif" alt="VoluStep 自定义音量曲线：低音量精细调节，高音量快速变化" width="480" />
</p>

## 功能

- **自定义音量曲线：** 拖动控制点设置每次按键的音量变化，并独立调整总按键次数。
- **点按与长按：** 点按移动一个映射档位，长按按设定的间隔持续调节。
- **固定音量按钮：** 保存常用音量，在应用内一键切换。

## 开始使用

支持 **Android 9（API 28）及以上版本**，提供简体中文和英文界面。

1. 从 [Releases](https://github.com/SPCDTS/VoluStep/releases) 下载并安装 APK。
2. 打开 VoluStep，阅读并同意权限说明，在 Android 设置中启用其无障碍服务。
3. 返回应用，打开主开关，调整音量曲线，即可使用实体音量键控制音量。

主开关用于启动或停止按键映射。固定音量按钮可独立使用，无需启用按键映射或无障碍服务。

## 设备支持与隐私

VoluStep 重新映射 Android 和连接设备已有的音量档位。设备决定最小可听音量和可用的调节精度；后台及熄屏时的表现因手机和固件而异。详见 [兼容性说明](docs/OEM_COMPATIBILITY.md)。

VoluStep 完全在设备本地运行。设置保存在应用本地存储中，无障碍服务只处理音量键事件。详见 [隐私说明](docs/PRIVACY.md)。

<details>
<summary>从源码构建与更多文档</summary>

需要 JDK 17、Android SDK 37 和 Build Tools 37。

```bash
./gradlew :app:assembleDebug
```

Debug APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`。

Windows 操作说明和完整构建流程见 [工具链配置](docs/TOOLCHAIN.md)。

[架构与曲线设计](docs/ARCHITECTURE.md) · [测试说明](docs/TESTING.md)

</details>

## 许可证

[MIT](LICENSE)。第三方依赖仍适用各自的许可证。
