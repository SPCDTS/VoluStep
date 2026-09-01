# 验证状态

本页只保留适合公开仓库的当前验证结论和复现入口，不保存局域网地址、设备序列号、PID、配对码、签名材料、临时产物哈希或逐轮调试流水账。测试设计与结果记录规则见 [TESTING.md](TESTING.md)。

最近验证日期：2026-09-01。

## 当前基线

| 项目 | 当前状态 |
|---|---|
| 测试入口 | 18 个 JVM + 12 个 Android instrumentation / E2E，共 30 个 `@Test` |
| JVM 单元测试 | `:app:testDebugUnitTest` 通过 |
| Debug 静态门禁 | `:app:lintDebug :app:assembleDebug` 通过 |
| AndroidTest 编译 | `:app:compileDebugAndroidTestKotlin` 通过 |
| Android instrumentation | API 37 AVD 完整 12/12 通过，0 skip、0 failure |
| 浅色/深色选中态 | API 37 AVD 四种点/线段画面已人工检查，无端点裁切 |
| Release | `:app:lintRelease :app:assembleRelease :app:bundleRelease` 通过；未签名产物只作构建门禁 |

最近一次界面改动将选中态改为独立的电紫色语义：控制点使用径向渐隐光晕与细环，线段使用三层荧光轨道，原曲线颜色不变；绿色仅表示当前音量。绘图区几何由生产代码和设备测试共享，避免调整端点留白后测试继续使用旧坐标。

## 复现命令

```powershell
. .\scripts\android-env.ps1

.\gradlew.bat `
  :app:testDebugUnitTest `
  :app:compileDebugAndroidTestKotlin `
  :app:lintDebug `
  :app:assembleDebug

$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest
```

宿主 E2E 只允许在可丢弃的 `emulator-*` 上运行：

```powershell
.\scripts\emulator-e2e-test.ps1 `
  -Serial emulator-5554 `
  -SkipBuild `
  -AppLocale zh-CN
```

脚本会临时修改模拟器的无障碍设置、音量和 adbd 身份，并在 `finally` 中恢复；不要对日常使用的真机运行。

## 已覆盖能力

- 整数控制点几何、严格单调投影、路由缩容与旧设置迁移；
- 短按、300 ms 长按起效、固定间隔积分、取消与真实音量重锚；
- 点/线段选择、上下文增删、按键次数容量和双轴整数吸附；
- 固定音量连续添加/删除，以及不依赖无障碍的可见界面真实写入；
- 显著披露、无障碍授权入口、前台控制器停止和中英文资源；
- Debug/Release Lint、R8 Release、APK/AAB 签名与隐私政策 metadata 校验脚本；
- API 28 最低版本安装启动，以及 API 36/37 模拟器和小米 HyperOS 的历史人工验证。

## 未宣称的范围

- 仓库没有 Pixel、Samsung、OPPO、vivo、Honor 等全部厂商的持续真机实验室；
- 蓝牙显示名称不能证明耳机硬件型号，A2DP 与 LE Audio 的可听档位也不能只靠系统回读推断；
- 1×1 无障碍窗口不是 AOSP 保活保证，不能恢复授权或抵抗 force-stop；
- Google Play 是否接受 Accessibility API 与 `specialUse` FGS 用途取决于发布时政策和审核；
- 未运行的设备、ROM、耳机或测试矩阵不得从本页的已有结果外推为通过。

## 提交验证记录

PR 或发布候选应至少记录：

1. commit 或明确的工作区状态；
2. 日期、API/ROM build 与设备类别；
3. 执行的完整命令；
4. pass/fail/skip 数量；
5. 与音频硬件相关时，区分系统回读一致和实际可听变化；
6. 截图、日志和诊断包在公开前完成序列号、局域网地址、账号与路径脱敏。
