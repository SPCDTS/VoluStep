# Play Console 声明草稿

更新日期：2026-08-25

本文件把当前实现整理成可复核的申报草稿，不替代 Play Console 当时显示的表单，也不能带占位符提交。每次上传新 AAB 前都要重新比对合并 Manifest、依赖、隐私政策和商店文案。

## 仍需发布者填写

- `[[PUBLISHER_LEGAL_NAME]]`：发布主体法定名称
- `[[PRIVACY_CONTACT_EMAIL]]`：公开隐私联系邮箱
- `[[FINAL_PRIVACY_POLICY_URL]]`：最终 HTTPS 隐私政策地址
- `[[ACCESSIBILITY_DEMO_VIDEO_URL]]`：无障碍声明演示视频
- `[[FGS_DEMO_VIDEO_URL]]`：`specialUse` 前台服务演示视频

## AccessibilityService API

### 用途类别

选择与“应用功能 / App functionality”相符的选项，不要声明 VoluStep 是面向残障人士的 accessibility tool。服务元数据保持 `isAccessibilityTool=false`。

### 核心功能说明草稿

> VoluStep lets a user define a deterministic mapping from presses of the phone's physical volume-up and volume-down buttons to Android media-volume indices. The AccessibilityService is required to detect those physical volume-button KeyEvents while the user is outside the app. Android may also deliver other physical-key events to the service; VoluStep checks the key code and immediately passes through non-volume keys. Every volume change is initiated by the user's volume-button press and follows the curve configured by that user. VoluStep does not autonomously initiate, plan, or execute actions.

### 数据访问和使用说明草稿

> For every physical-key event delivered by Android, the service checks the key code and immediately ignores and passes through non-volume keys. For volume-up and volume-down, it temporarily processes the key code, press/release state, repeat count, event time, down time, and input-device identifier needed to distinguish one press, detect a hold, and reject stale events. It does not retrieve window content, on-screen text, touch input, screenshots, passwords, accounts, or content from other apps. Key-event data is not persisted, uploaded, sold, or shared. The app has no Internet permission and contains no advertising or analytics SDK.

### 显著披露与同意路径

审核视频必须连续展示：

1. 清除应用数据后首次打开 VoluStep；
2. 应用在正常流程中独立显示无障碍显著披露；
3. 用户未勾选时“同意”保持禁用；点击“取消”，确认没有进入系统设置、没有启动控制器、也没有保存同意；
4. 再次点击主开关，确认显著披露重新出现；
5. 用户主动勾选并确认后，应用才进入系统无障碍设置；
6. 用户在系统页主动启用“VoluStep 音量控制”；
7. 回到应用并主动打开 VoluStep 主开关；
8. 切换到其他应用或桌面，用手机实体音量键展示自定义映射；
9. 回到 VoluStep 关闭主开关，再展示系统恢复默认按键行为；
10. 展示系统中关闭无障碍服务的第二种停止方式。

视频不要剪掉披露、勾选、系统授权和停止步骤；画面或字幕应说明耳机自身按键可能绕过 KeyEvent 链路。填入：`[[ACCESSIBILITY_DEMO_VIDEO_URL]]`。

## `specialUse` 前台服务

### 功能说明草稿

> VoluStep uses a specialUse foreground service only while the user-enabled global physical-volume-button mapping is active. The user starts it from the visible main Activity with the VoluStep switch after completing the disclosure and system Accessibility authorization. It keeps the user-requested mapping state available so a physical button press can be handled immediately. It does not play media, synchronize data, access location, or start itself at boot.

### 延迟或中断的影响草稿

> If startup is deferred or the service is interrupted, presses outside the visible Activity cannot reliably use the user's curve and Android falls back to its default volume behavior. The feature is interactive: delaying a response until later would be incorrect and could cause an unexpected volume jump. The app therefore stops consuming the key whenever it cannot safely complete the mapping.

### 用户感知、时长与停止方式草稿

> The service runs only while the user keeps the VoluStep switch enabled. The active state is shown in the app and in Android's Running apps surface, where the user can stop it; the app switch provides the primary stop control. Android 13 and later builds do not request notification permission, so the required foreground-service Notification object is not shown in the ordinary notification drawer. The controller never starts from boot, an app update, or an Accessibility callback.

审核视频必须展示主开关启动、曲线生效、应用进入后台、系统“运行中的应用”入口、应用主开关停止及系统恢复默认行为。填入：`[[FGS_DEMO_VIDEO_URL]]`。

`specialUse` 会逐案审核。不要把用途误报为 `mediaPlayback`、`connectedDevice` 或其他类型；如果审核不接受，应采用侧载/企业分发或重新设计可见 Activity 方案，不能通过伪造类型规避。

## Data safety

只有最终 AAB 仍满足下列事实时，才选择“不收集、不共享用户数据”：

- 没有 `INTERNET` 权限、网络客户端、广告、分析、崩溃上报或账号 SDK；
- 音量键事件只在内存中处理，不持久化；
- 映射曲线、长按间隔和披露确认只存入应用私有 DataStore，并从云备份和设备迁移中排除；
- 厂商、输出路由名称、媒体音量范围和当前 index 只在本机显示；
- 没有服务器账号、云同步、远程日志、出售或第三方共享。

用户可关闭主开关，让应用停止映射并放行音量键；关闭无障碍服务后，系统会停止向 VoluStep 投递按键事件。清除应用存储或卸载会删除私有配置。由于没有服务器端数据，不存在服务器删除请求流程。

## 应用访问与审核备注

VoluStep 无账号、付费墙或地域限制。审核人员需要一台有实体音量键的 Android 设备或模拟器，并按以下顺序使用：首次披露 → 系统无障碍授权 → 返回应用打开主开关 → 在桌面或其他应用按手机音量键。耳机自身按键、某些 OEM 后台策略和固定音量输出可能不触发映射，应用详情见商店文案的兼容性边界。

建议商店类别选“工具 / Tools”；广告声明为“无广告”。目标受众年龄范围和内容分级属于发布者的产品/法律选择，必须在 Play Console 中据实完成，本文不替发布者决定。

## 官方政策入口

- [Accessibility API 政策](https://support.google.com/googleplay/android-developer/answer/16558241)
- [AccessibilityService API 说明](https://support.google.com/googleplay/android-developer/answer/10964491)
- [显著披露与同意](https://support.google.com/googleplay/android-developer/answer/11150561)
- [前台服务声明要求](https://support.google.com/googleplay/android-developer/answer/13392821)
- [前台服务权限政策](https://support.google.com/googleplay/android-developer/answer/16559646)
- [新个人开发者账号测试要求](https://support.google.com/googleplay/android-developer/answer/14151465)
