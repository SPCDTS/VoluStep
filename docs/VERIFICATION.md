# 本机验证记录

## 2026-08-31 选中态荧光增强

本轮只增强曲线编辑器的选中态光晕：控制点和线段仍保持原本的中性色，外层与内层光晕透明度分别从 `0.10/0.16`、`0.10/0.22` 统一提高到 `0.18/0.34`；半径、线宽、虚线坐标和绿色当前音量标记均未改变。

验证结果：

- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 为 `BUILD SUCCESSFUL`，源码仍为 18 条 JVM + 12 条 instrumentation / E2E，共 30 个 `@Test`；
- API 37 AVD 上分别截取浅色、深色的选中控制点与选中线段画面并目视核验：双层光晕清晰可辨，未选对象、曲线本体和绿色当前音量线没有视觉回归；
- `MainActivityTest#selectedSegmentInsertionAndSelectedPointDeletionFormAnExactRoundTrip` 与 `MainActivityTest#draggingAControlPointHardSnapsBothAxes` 两条直接相关的设备测试均通过；
- 全量 12 条设备测试在第 3 条无关的 `overflowProvidesInAppPrivacyAndVersionInformation` 语言切换/窗口重建阶段长时间不收敛，日志没有断言失败，已终止无效等待；本轮未把该基础设施挂起误记为通过。

## 2026-08-30 无障碍运行锚点与最近任务隐藏

本轮把直接分发构建调整为更接近李跳跳的运行形态：`VolumeKeyAccessibilityService` 连接后持有一个透明、不可触摸、不可聚焦的 1×1 `TYPE_ACCESSIBILITY_OVERLAY`，添加失败时进行有限延迟重试并在解绑/销毁时移除；Launcher Activity 从任务创建起始终排除最近任务。Android 17 后台音量修改所需的 `specialUse` FGS 仍保留，因为运行锚点不能替代该资格。

验证结果：

- 源码仍恰好是 18 条 JVM + 12 条 instrumentation / E2E，共 30 个 `@Test`；运行锚点和最近任务声明断言并入原有系统生命周期 E2E，没有增加测试入口或把无关职责塞进单个测试；
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:bundleRelease` 为 `BUILD SUCCESSFUL`；API 37 AVD 上直接运行 AndroidJUnitRunner 为 12/12 通过、0 failure、0 skip；
- AndroidJUnitRunner 实际完成无障碍 disable → enable，确认目标包专属窗口标题存在，窗口 dump 同时包含 `1x1`、`ACCESSIBILITY_OVERLAY`、`TRANSPARENT`、`NOT_FOCUSABLE`、`NOT_TOUCHABLE`；
- `scripts/emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild -AppLocale zh-CN|en-US` 两次均为 7/7 通过：按 Home 后同一 task 保留且带 `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`，FGS 与无障碍继续运行；后台 evdev 短按映射 `5 → 11 → 5`，持续按住 360 ms 到 `13`，停止后由系统 `AudioService` 恢复接管；
- 小米真机 `192.168.3.6:46747` 首次安装被 HyperOS“应用安装拦截”以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝；用户允许后再次安装成功，Debug 版本为 `1.0.0-debug`。前台任务根 Intent 为 `0x10800000`、`isExcluded=true`；按 Home 后进入最近任务的截图中没有 VoluStep 卡片，同一个 task、PID `18100`、FGS 和 Bound AccessibilityService 均保持，exit-info 没有退出记录；
- 真机上李跳跳与 VoluStep 同时 Enabled/Bound。两者各自在屏幕左下角持有一个 `frame=[0,2669][1,2670]` 的窗口；VoluStep 窗口实际为 `TYPE_ACCESSIBILITY_OVERLAY`、`TRANSPARENT`、`NOT_FOCUSABLE`、`NOT_TOUCHABLE`、`HARDWARE_ACCELERATED`，Surface shown。应用后台时实体短按一次音量加，媒体 index 从 `70 → 72`，AudioService 明确记录 `setStreamVolume(... index:72 ... oldIndex:70) from dev.spcdts.volumemapper.debug`，PID 未变化；

该窗口是 OEM 经验性运行锚点，不是系统保活保证：撤销无障碍、真正 force-stop、重启或厂商不再分发按键时，应用仍不能自行恢复。若改走 Google Play，需在发布前重新审核 Accessibility API 政策与披露，必要时拆分不含该策略的发布变体。

## 2026-08-29 固定音量快捷值

本轮在主界面曲线下方增加“固定音量”卡片。用户可以在底部编辑层添加、删除并持久化实际媒体音量 `index`；快捷按钮按数值排序，当前值只使用绿色描边与光晕，不改变按钮底色。超出当前输出路由范围的既有值会保留但禁用，仍可进入编辑层删除。快速连续添加、删除使用仓库内的原子更新，不依赖 Compose 重组时机。

固定值点击由可见 `Activity` 直接调用公开 `AudioManager` 音量接口，不要求无障碍授权、映射总开关或后台前台服务。写入前会重新确认可见性、媒体上下文、输出路由、音量范围和固定音量设备状态；写入与实体按键共用串行 actor，随后分两阶段回读。路由切换、请求替换、实体键抢占、系统静默拒绝或回读竞态都会得到确定的完成、重试或拒绝状态，不会让按钮长期停在处理中，也不会计入后台映射的 fail-open 失败次数。

验证结果：

- 当前源码恰好包含 30 个独立 `@Test` 入口：18 个 JVM 测试和 12 个 Android instrumentation / E2E 测试；没有把新增断言集中塞入单一测试方法；
- API 37 AVD `VolumeMapper_API_37` 上 `:app:connectedDebugAndroidTest` 为 12/12 通过，0 skip、0 failure；其中两项固定值测试分别覆盖编辑层连续添加/删除，以及关闭映射总开关、未连接无障碍时通过真实 `AudioManager` 写入、精确回读、当前按钮与曲线同步，并在 `finally` 中恢复原媒体音量；
- `MainActivityTest` 为 8/8 通过；语言恢复前会先卸载 Privacy/About 对话框，完整套件不再因旧 Dialog Window 与 Activity 重建并存而等待；
- `:app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:bundleRelease`：`BUILD SUCCESSFUL`；18/18 JVM 测试通过，Debug/Release lint 均为 0 error、6 个非阻断的版本或既有 API 建议；Debug APK、R8 Release APK 与 Release AAB 均成功生成；
- 中文与英文分别执行 `scripts/emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild -AppLocale zh-CN|en-US`，两次均为 7/7 宿主断言通过：划掉任务卡片后实体按键短按完成 `5 → 11 → 5`，持续按住 360 ms 到 `13`，停止映射后由系统 `AudioService` 恢复接管；
- 当前 Debug APK SHA-256 为 `7305F3AFF798B617FBA0166DE5558F8694D5AA8820496F64AEFAB6532963581D`；未签名 Release APK 为 `F3E1460A1D1EAB3F5CA8E42137B64BADD6E03090430172BA688172C0E894846A`，未签名 Release AAB 为 `1F7EA73B0DA84574D110FBAFDF3E45BEA5BAF6D21DB60AF173CFB4D926115F86`，后二者不能直接发布；
- 收尾时 `adb devices -l` 与 mDNS 只发现 API 37 模拟器，先前的小米地址 `192.168.3.6:35819` 已拒绝连接。因此本节不把模拟器的直接音量写入结论冒充为 HyperOS / 蓝牙耳机真机结果；手机重新开启无线调试并提供当前连接端口后仍应补一次覆盖安装与可听档位回归。

## 2026-08-25 发布加固工作区门禁

本节记录执行时尚未提交的发布加固工作区。当前源码恰好包含 30 个唯一 `@Test` 入口：20 个 JVM 测试和 10 个 Android instrumentation / E2E 测试；后者由 `MainActivityTest` 8 项、真实 `AudioManager` 集成 1 项和模拟器真实系统链路 1 项组成。宿主 PowerShell E2E 的 7 项断言独立于这 30 个入口，不重复计数。

已确认结果：

- 使用 `--no-daemon --rerun-tasks` 强制执行 129 个 Gradle task：`:app:testDebugUnitTest` 为 20/20 通过；`:app:compileDebugAndroidTestKotlin`、`:app:lintDebug`、`:app:lintRelease`、`:app:assembleDebug`、`:app:assembleRelease` 和 `:app:bundleRelease` 均通过，Debug/Release lint 各 0 个 blocking issue；
- 在 API 37 AVD `VolumeMapper_API_37`（Android 17）上显式指定 `ANDROID_SERIAL=emulator-5554` 后，`:app:connectedDebugAndroidTest` 为 10/10 通过，0 skip、0 failure；
- 在 API 36 AVD `VolumeMapper_API_36`（Android 16）上，`:app:connectedDebugAndroidTest` 为 10/10 通过，0 skip、0 failure；
- 在 API 28 AVD `VolumeMapper_API_28`（Android 9）冷启动后，使用 Android CLI 安装并启动 `app-debug.apk` 成功；layout 可见 VoluStep、主开关、更多选项、控制点、音量图、按键次数、长按间隔、设备区域及当前音量语义，截图保存在被 Git 忽略的 `artifacts/api28-launch.png`。本轮没有在旧版本执行完整宿主 E2E，仅完成最低支持版本的安装、启动与首屏基础检查，符合当前不过度兼容旧 Android 的范围；
- 第一次在多设备环境直接运行时，Xiaomi Wi-Fi ADB 序列号中的冒号触发 Windows UTP 路径问题，同时真机拒绝测试 APK，因此该次 Gradle 任务失败属于设备选择与测试基础设施失败，不是测试断言失败；显式限定 `emulator-5554` 并冷启动 AVD 后通过；
- `RealSystemVolumeE2eTest` 的首次流程已补充并实际验证：未勾选时不能同意，取消后不保存同意、不进入授权、不启动控制器，再次触发后才可主动同意；同意后直接进入系统无障碍设置。披露同时准确说明系统可能投递其他实体按键、应用检查键码后立即放行非音量键，以及音量事件只在内存中临时使用的字段；
- `scripts/emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild -AppLocale zh-CN` 的 7/7 宿主断言通过：真实披露 instrumentation 完成，应用退到后台后 evdev 实体按键链路按映射完成 `5 → 11 → 5`，从主开关停止后由系统 `AudioService` 恢复接管；fail-open 阶段媒体音量保持 `5 → 5`、铃声音量保持 `2 → 2`，判据是系统调节调用重新出现而不是强制要求当前非活动流的 index 改变；脚本退出码为 0，清理无报错；
- `scripts/emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild -AppLocale en-US` 的 7/7 宿主断言通过：instrumentation 返回的英文资源契约正确，应用退到后台后 evdev 映射完成 `5 → 11 → 5`，停止后由系统 `AudioService` 恢复接管；脚本退出码为 0，清理无报错；
- API 36 的 `scripts/emulator-e2e-test.ps1 -Serial emulator-5556 -SkipBuild -AppLocale zh-CN` 同样 7/7 通过，后台 evdev 映射为 `5 → 11 → 5`，停止后由系统接管且清理成功；完成后已停止该后台 AVD；
- Release APK 黑盒检查确认包名 `dev.spcdts.volumemapper`、版本 `1.0.0 (1)`、min/target API `28/37`；权限只包含 `MODIFY_AUDIO_SETTINGS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` 和构建系统生成的非导出动态 receiver 权限，没有 `INTERNET`、`POST_NOTIFICATIONS`、存储、账号或蓝牙权限；
- Intent 导出边界只读复核通过：Launcher Activity 不处理外部 payload；无障碍服务由系统级 `BIND_ACCESSIBILITY_SERVICE` 权限保护；映射 FGS 与 AndroidX Startup Provider 均不导出；Profile Installer receiver 虽导出但受系统级 `android.permission.DUMP` 保护；两个 PendingIntent 都是显式目标且使用 `FLAG_IMMUTABLE`，无需修改源码；
- 未签名 Release APK/AAB 被 `verify-release-artifacts.ps1` 正确拒绝；一次性测试证书下的签名 APK/AAB 已通过 zipalign、包名/版本、APK/AAB 唯一证书与离线指纹核验。候选包还必须在 APK 与 AAB Manifest 中携带同一个预期 HTTPS 隐私政策 URL；不同 URL、APK 空 URL、AAB 空 URL 三种负向组合均被拒绝。只提供部分 Gradle 签名变量也会失败，不会静默回退到未签名构建。重复归档不会覆盖既有候选包；测试密钥与签名输出随后删除，仓库工作区外的 SDK CA 文件除外，不存在项目生产密钥；
- 当前未签名门禁产物为 `1.0.0 (1)`：Debug APK SHA-256 `0833928D2DED29D560750F030B19963BA77F80BCA4B757B8DB137A9BA0351875`，未签名 Release APK `EF0AC28CAB34705C5AAB93A5634E146060298FE34DD3048F08D10BE16433B76D`，未签名 AAB `0C49EE9A3A1AED4665243CB4A49C32D465C15AFDBE991816008429B67F70F94A`；后二者不可发布；
- 商店图标、功能图与四张中英文截图的尺寸分别为 `512×512`、`1024×500` 与 `1080×2400`，Fastlane 机械副本与源文件 SHA-256 完全一致；截图已在最终 Debug 构建上重拍并人工检查。Debug 不注入最终隐私 URL，因此隐私截图没有正式包的“完整政策 / Full policy”按钮；最终地址就绪后必须使用签名候选包再次重拍；
- `git diff --check` 通过。

## 2026-08-25 点选择插入与 Music Streaming 色卡

控制点数量的 `+` 现在同时支持点选择和线段选择：选中线段时在该段插入；选中普通控制点时在其右侧线段插入；选中最后一个控制点时改用左侧线段。目标段没有空余整数 X/Y 时保持禁用且不自动换边，插入后选中新点。首次进入编辑器按控制点顺序选中中间点，偶数数量固定取右中点。

界面参考 Music Streaming 色卡完成中性化：深色主题使用 `#121212`、`#1DB954`、`#FFFFFF`、`#282828`、`#535353`，浅色主题使用同一语义的高对比派生色。主曲线为黑白中性色，当前音量独占绿色，点与线段选中后只叠加同色光晕；adaptive icon 同步改为黑底白色上升曲线与绿色光晕。启动窗口和系统栏分别提供浅色、深色资源，避免冷启动错色。

验证结果：

- 该阶段测试入口为 30 条；点右插、末点左插、选段插入、删除闭环和首次中点选择分别由职责清晰的 UI 测试覆盖；当前拆分及最新结果见本日最上方记录；
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 为 `BUILD SUCCESSFUL`，lint 0 error，`git diff --check` 通过；
- Android CLI 在 API 37、1080×2400 模拟器分别切换浅色与深色模式检查：图表、轴标签、禁用控件、选中光晕与绿色当前音量线均清楚，无裁切或重叠；首次画面选中第 3/5 个控制点，坐标为 x=6、index=3，`+` 可用；
- 最新 Debug APK 已通过标准 ADB 覆盖安装到小米真机 `192.168.3.6:34195`，版本 `0.1.0-debug`；原无障碍授权及服务绑定保留，前台映射控制器因覆盖安装停止，需在主界面重新打开总开关。

## 2026-08-25 VoluStep 品牌与中英本地化

本轮把正式应用名统一为 VoluStep，默认资源使用英文，简体中文放在 `values-zh-rCN`；界面、显著披露、无障碍语义 action、运行状态和前台服务文案均通过 Android 字符串资源解析。运行时状态保存资源标识与格式化参数，不在无 Activity 的协调器中提前固化某一种语言。

测试总量继续限制为 30 个唯一 `@Test` 入口：首屏资源与布局测试固定覆盖英文，无障碍点/线段 action 测试固定覆盖简体中文，其余行为测试从当前 locale 的资源构造语义期望；没有把语言矩阵扩成新的参数化测试或巨型聚合方法。宿主 E2E 新增 `-AppLocale en-US|zh-CN`，由现有 instrumentation fixture 把当前语言下的主开关描述和通知标题传给 PowerShell，脚本不再硬编码中文选择器。

当前已执行 `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:bundleRelease`：`BUILD SUCCESSFUL`，当时 30 个测试入口全部通过，lint 为 0 error；当前拆分及最新结果见本日最上方记录。英文 `en-US` 与简体中文 `zh-CN` 分别复用同一条宿主 E2E 旅程，两次均验证划掉任务卡片后后台映射 `5 -> 11 -> 5`，关闭主开关后由系统恢复默认按键处理；两种语言的主界面截图也已人工检查，VoluStep 品牌名保持一致且未发现截断或重叠。最终 Debug APK 已通过标准 ADB 覆盖安装到小米真机，版本为 `0.1.0-debug`，原无障碍授权仍在；覆盖安装会停止前台控制器，需由用户在可见界面重新打开主开关。PowerShell E2E 脚本语法检查和 `git diff --check` 通过。

## 2026-08-25 API 33+ 静默前台服务

本轮保留 Android 17 后台音量修改所需的 `specialUse` FGS，但移除了 `POST_NOTIFICATIONS` Manifest 声明、运行时权限请求、拒绝弹窗和启动门槛。服务仍向 `startForeground()` 提交平台要求的 `Notification` 对象；Android 13 及以上不在普通通知抽屉显示该控制器，系统“运行中的应用”入口仍可见。应用主开关继续提供明确的启动与停止入口，设备区后台状态由“允许”改为“运行中”。

验证结果：

- 当时 30 个测试入口全部通过；没有新增巨型聚合测试，当前拆分及最新结果见本日最上方记录；
- `RealSystemVolumeE2eTest` 从空白数据完成显著披露、真实 AccessibilityService 和 FGS 启动，确认合并 Manifest 不声明 `POST_NOTIFICATIONS`，且控制器仍处于前台服务状态；
- 宿主 `emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild`：PASS。脚本实际展开 SystemUI 并确认目标标题不在通知抽屉；随后划掉本应用最近任务卡片，确认 FGS 与无障碍仍在，evdev 音量键按确定性映射完成 `5 → 11 → 5`；重新打开应用通过主开关停止后，系统 `AudioService` 恢复接管；
- `scripts/build.ps1 -Release`：`BUILD SUCCESSFUL`。Debug lint、Debug APK、R8 Release APK、Release AAB 与 Release lint vital 均通过；
- `aapt2 dump badging` 对 Debug 与 Release APK 的黑盒检查均只看到 `MODIFY_AUDIO_SETTINGS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` 及构建系统生成的非导出 receiver 权限，没有 `POST_NOTIFICATIONS`。

本轮验证设备为 API 37 模拟器。Xiaomi 真机在收尾时未出现在 `adb devices`，因此没有把 AOSP 模拟器的通知抽屉与划卡结论冒充为 HyperOS 真机结论；真机重新连接后仍应补一次“启动映射—划掉任务卡片—通知抽屉为空—FGS/映射继续”的回归。

## 2026-08-25 正式单屏 UI、选择式增删与图标对齐

本轮按正式发布界面收口为单一主屏：顶部只保留应用名“VoluStep”与总开关，曲线卡片承载全部高频编辑，设备与授权信息收纳在可折叠的“设备”区域；不再提供 Tab、常驻诊断页、Undo/Redo 或按键说明菜单。

本轮曲线编辑与图标目标如下：

- “控制点数量”位于图表上方并只读显示 P；`−` 只删除选中的内部点，`+` 在选中线段中插入，或在选中点的右侧段插入（末点改用左侧段），且目标段必须具有空余整数 X/Y。新增后选中新点，删除后选中合并线段，端点不可删除；
- “按键次数”位于 x 轴下方，继续支持直接输入整数和 `−` / `+` 单步调整，满足 `2≤P≤min(16,K+1)` 与 `K≥P−1`；
- 控制点与线段使用互斥选择和不同视觉反馈；画布扩大并压缩卡片、坐标轴周围的无效留白，使曲线尽可能占用手机屏幕；
- 中间控制点可同时调整 x/y：x 无条件吸附到整数按键位置，y 吸附到整数 audio index；选中点始终以两条虚线延伸到坐标轴，坐标值直接标在相应刻度行；
- 图中不再绘制每个按键对应的采样小点；y 轴的常规刻度配有静音、低、中、高音量图标；当前系统音量用绿色水平线、交点和“当前 n”标示，不显示当前值的 x 坐标；
- 设置格式升级为保存整数 `(pressPosition, offset)` 的 v4，旧 v1–v3 曲线在载入时确定性投影到整数网格；
- adaptive launcher icon 当前为黑色背景、白色上升映射折线、等大节点和单个绿色光晕；themed icon 使用相同单色轮廓，不包含品牌耳机或复杂波形；
- 图表下方保留长按间隔设置；设备名称、音量范围、无障碍与后台状态统一放入设备折叠区。

验证结果：

- 当时 30 个测试入口全部通过，API 37 模拟器设备测试为 0 skip、0 failure；显式插入、删除、容量与无障碍行为已拆成独立测试，不依靠巨型聚合方法凑数。当前拆分及最新结果见本日最上方记录；
- `:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug :app:bundleRelease`：`BUILD SUCCESSFUL`；Debug lint 无阻断项，Debug APK 与 Release AAB 均生成成功；
- 宿主 `emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild`：PASS。脚本从空白数据完成显著披露、真实 AccessibilityService、`specialUse` FGS、后台 evdev 音量键与 SystemUI 通知停止，得到映射 `5 → 11 → 5`，停止后由系统 `AudioService` 接管；清理阶段恢复无障碍、音量、adbd 和通知面板；
- Android CLI 在 API 37、1080×2400 模拟器上确认正式版只有一个主界面，画布占用主要屏幕空间，P 只读、K 可输入，点与线段均可命中；浅色和深色模式下，选中对象保持原曲线颜色，只用同色多层光晕高亮，点坐标虚线和当前音量水平线层级清楚；
- 模拟器启动器页渲染确认 adaptive icon 在小尺寸仍能辨认上升折线与四个等大节点，强调节点本体不换色，仅增加绿色光晕；
- 最终主界面与线段选择截图保存在 Git 忽略的 `app/build/final-ui.png`、`app/build/final-segment-selection.png`，明暗模式与图标截图保存在 `app/build/visual-*.png`；模拟器在检查后已恢复浅色模式。

本节验证针对模拟器上的本轮正式 UI 与公开 Android 音量链路，不替代 Xiaomi / HyperOS 和真实蓝牙耳机的厂商矩阵复测。

## 2026-08-24 自由控制点与正式单页收口

本轮把“从最小到最大需要的按键次数 `K`”与“搭建折线的控制点总数 `P`（含两端）”完全解耦，并允许中间控制点保存自由 x。只有 `K+1` 个实际按键位置按 `n/K` 均匀分布；控制点在 x 轴吸附到邻近按键位置、在 y 轴吸附到整数 audio index。保存格式升级为 `v3|basisSpan|K|x,offset;…`，同时保留 `v1` / `v2` 和 legacy shadow 迁移。

验证结果：

- `scripts/build.ps1 -Release` 与补充的 `:app:lintRelease`：通过；22/22 个 JVM 场景组无 failure、error 或 skip，Debug / Release lint 均无阻断项，R8/资源压缩后的未签名 Release APK 与 AAB 均成功生成；原细粒度断言保留在场景组内；
- 最终 Release lint 为 0 error、3 个工具/依赖版本更新 warning、1 个 Compose 状态类型优化 hint；Debug APK、未签名 Release APK、Release AAB 的 SHA-256 依次为 `AEC89128D0AA16B5BC02746ECCD823F6A1024477C019C5C81A6E87EE46FB2B14`、`01922E018D74A203C0CF3FC589D8B5B757C8C6E8802737E113ED9A5CFE3CC8D9`、`4DCF3EF9383486EDADBDA1A9F7938D2D46104FEBC1C07298FB1E615BE88A334C`；
- `:app:connectedDebugAndroidTest`：API 37 模拟器 8/8 通过，0 skip、0 failure；其中单页 UI 6 项，真实 `AudioManager` 集成 1 项，显著披露、真实 AccessibilityService 与 `specialUse` FGS 1 项；通知停止部分通过 `dumpsys` 核验通知及 action 契约，不等同于点击 SystemUI；与 JVM 合计 30 个测试入口；
- `scripts/emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild`：通过，真实 AccessibilityService、`specialUse` FGS 与 evdev 链路完成后台映射 `5 → 11 → 5`；宿主脚本实际展开 SystemUI 并点击“停止映射”，停止后由系统接管；
- 视觉与交互检查使用 Android CLI 和 1080×2400 的 `VolumeMapper_API_37`：全新数据默认 `basis=30、K=18、P=5`，0…15 路由按实际 index 显示；浅色与深色模式均确认正式版只有一个主界面，当前音量为绿色水平线、交点和“当前 n”标签，y 轴音量图标不与刻度重叠，`P` 位于图上、`K` 位于 x 轴下、长按间隔与折叠设备区均无裁切。截图保存在被 Git 忽略的 `app/build/verification/final-*.png`。

本轮没有连接 Xiaomi 真机或蓝牙耳机，因此这里只确认模型、持久化、Android 公开音量链路和模拟器实体按键分派；耳机端可听档位仍需在目标设备上复测。

## 2026-08-24 简约 UI 中间阶段（历史）

本节记录正式单页收口前的中间工作树；最终界面、测试数量和交互语义以上一节为准。

本轮把高频操作留在默认层，把精确输入、按键响应、设备底层信息和后台排障建议改为按需展开；同时统一为单一蓝色强调色、中性 surface，并支持系统明暗模式。显著披露、fail-open、路由降级和固定音量提示没有因简化界面而删除。

验证设备为 `VolumeMapper_API_37`，序列号 `emulator-5554`，分辨率 `1080×2400`，Android 17 / API 37。执行结果：

- `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:bundleRelease`：通过；最终与设备测试合并执行时共完成 149 个 Gradle task；
- `:app:connectedDebugAndroidTest`：3/3 通过，0 skip、0 failure；覆盖主导航与折叠设置、真实 `AudioManager` 集成，以及显著披露、无障碍连接、前台控制器和通知停止流程；
- `scripts/emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild`：通过。宿主从空白应用数据开始完成披露，后台 evdev 音量键按离散映射完成 `5 → 11 → 5`，再从 SystemUI 常驻通知停止控制器并验证 fail-open；脚本在 `finally` 中恢复模拟器无障碍、音量与 adbd 状态；
- 使用 Android CLI 检查控制、曲线默认层、精确编辑、诊断折叠区，并分别在系统浅色与深色模式目视复核；浅色状态栏使用深色系统图标，深色模式使用浅色图标。强制显示数字软键盘时，聚焦的 `K` 输入仍保持可见。没有发现 1080×2400 窄屏上的横向溢出、系统栏遮挡或不可达控件。截图保存在被 Git 忽略的 `artifacts/minimal-*.png`。

本轮只有 API 37 模拟器连接，未把这次纯 UI 改动冒充为新的 Xiaomi / 蓝牙耳机真机验证；下方既有真机音频链路结论仍对应当时安装包与记录条件。

## 2026-08-24 离散按键曲线重构

本节对应从基线 `c96f32b` 开始的早期离散曲线工作树。该中间阶段把连续 `x → V`、短按百分比和 dB 量化改为均匀控制点横轴与整数 index 状态表；最终版本已经继续演进为“控制点自由 x、只有按键位置均匀”，语义以上方最新记录和 [CURVE_EDITOR.md](CURVE_EDITOR.md) 为准。下方更早记录中的“1% / 0.7% / 40% 短按步长”是当时安装包的历史配置名称，不代表当前界面仍提供这些选项。

### 主机门禁

最终源码执行以下门禁并全部成功：

```powershell
. .\scripts\android-env.ps1
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin `
  :app:lintDebug :app:lintRelease :app:assembleDebug `
  :app:assembleRelease :app:bundleRelease --rerun-tasks
```

覆盖包括 `K` 次按键 / `K+1` 状态不变量、严格整数投影、点数变化、0–15 / 0–150 / 非零 min / fixed-range、画笔推挤、离散 reducer、旧设置迁移、exact-index coordinator 连续性和 Android 测试源码。Debug/Release lint 均无阻断项，Debug APK、R8 Release APK 与 Release AAB 均生成成功。

### API 37 模拟器

验证 AVD 为 `VolumeMapper_API_37`，序列号 `emulator-5554`，build fingerprint 为 `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys`。

- 全量 instrumentation：`MainActivityTest`、`RealSystemVolumeE2eTest`、`VolumeKeyAudioIntegrationTest` 共 3 项，结果 `OK (3 tests)`；最终 UX 提示调整后又定向重跑 `MainActivityTest`，结果 `OK (1 test)`。
- 宿主 `emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild` 从空白应用数据完成显著披露、真实 AccessibilityService、`specialUse` FGS、通知停止和内核 evdev 按键链路，结果为后台映射 `5 → 11 → 5`；停止后检测到系统 `AudioService.adjustSuggestedStreamVolume`，fail-open 通过。
- 使用 Android CLI 分别检查曲线主画布和精调控件：固定等距列、选中点、`Δindex` 条带、次数设置、index 输入与撤销/重做在 1080×2400 模拟器上未见裁切或不可达；本地截图保存在被 Git 忽略的 `artifacts/curve-editor-*.png`。
- 最终只读清理复核：adbd 为 `uid=2000(shell)`，`enabled_accessibility_services=null`，目标服务列表为空，媒体音量为 `5/15`。

本轮结束时先前的小米真机序列号 `112594e4` 已不在 `adb devices` 中，因此没有把这一版 APK 重新安装到真机，也不把旧安装包的真机结果冒充为本次离散曲线通过。重新连接后仍需补一次覆盖安装、设置迁移、0–150 曲线编辑和实体按键回归。

## 2026-08-23 至 2026-08-24 Xiaomi 15 真机验证

验证设备为 Xiaomi 15，HyperOS，Android 16 / API 36。本节结果只代表这一台手机、当前 ROM 和本次连接的输出设备，不外推到其他小米设备、其他厂商或同名耳机。蓝牙设置显示的名称为“Xiaomi Buds 5 Pro”，但没有取得可验证的硬件型号，因此下文只称其为该显示名称的耳机，不能据此确认具体型号。

### 扬声器与实体按键

系统运行时报告扬声器媒体音量范围为 `0..150`。在线性曲线、1% 短按步长、按 index 量化配置下，实体音量键得到以下结果：

| 场景 | 结果 |
|---|---|
| Activity 位于前台 | `62 → 63 → 62`，`AudioService` 记录的 `setStreamVolume` 调用方为 `dev.spcdts.volumemapper.debug` |
| Activity 位于后台、显示桌面 | `62 → 63 → 62` |
| 亮屏锁屏，`showing=true`、`inputRestricted=true` | `62 → 63 → 62` |
| 熄屏 Doze、无媒体播放 | 按键未触发应用或系统音量写入，媒体音量保持 `62`；该场景不记为映射通过 |

目标无障碍服务与设备原有的另一个无障碍服务同时保持 Bound，未观察到服务崩溃。`specialUse` FGS 正常运行，持续通知和停止入口可见。

### 蓝牙 A2DP 样本

连接上述显示名称为“Xiaomi Buds 5 Pro”的耳机后，系统实际选择 A2DP / AAC 路由，AVRCP 绝对音量为 `true`，Android 媒体范围仍为 `0..150`，测试起始 index 为 `8`：

- 手机实体侧键完成 `8 → 9 → 8`，并观察到 `setAvrcpVolume` 同步；
- 耳机自身音量手势没有进入本应用的无障碍按键链路，而是由 `com.android.bluetooth` 直接完成 `8 → 18` 和 `18 → 8`，单次变化为 10 个 Android index；
- 外部音量变为 `18` 后，再按手机侧键得到 `18 → 19`，证明应用会从当前回读值继续映射，而不是沿用旧的逻辑音量。

以上只证明无媒体播放时该次 A2DP / AAC、AVRCP 绝对音量链路的 index 同步，LE Audio 尚未验证。

### 真实媒体播放暴露的失败

在哔哩哔哩实际播放视频、系统报告媒体会话 active 且 `mIsPlaying=true`、测试人员确认耳机可听见声音后，将本应用 Activity 置于后台并短按手机音量加，观察到：

1. HyperOS 在 Accessibility 按键分派等待窗口内没有取得本应用回调，日志记录 key dispatch timeout；
2. 系统默认路径随后把 A2DP index 从 `8` 调到 `18`，又调到 `28`；
3. 约 81 秒后重新打开本应用 Activity 时，旧安装包才收到此前的 `DOWN` / `UP`，并额外写成 `29`、`31`。

该场景判定为失败，不计入精细映射通过。AMS 当时仍把本应用记录为带前台服务的进程，`procState=4`、`cached=false`、`isFrozen=false`；现有证据只能说明 HyperOS 延迟并超时了无障碍按键回调，不能宣称进程已被冻结。

针对迟到事件，当前实现使用 500 ms 新鲜度边界：过期初始 `DOWN` 不接管也不入队；匹配现有 owner 的过期尾事件会清理既有手势，不再进入 reducer 执行短按收尾写。500 ms 来自 [AOSP Accessibility `KeyEventDispatcher`](https://android.googlesource.com/platform/frameworks/base/+/master/services/accessibility/java/com/android/server/accessibility/KeyEventDispatcher.java) 的固定等待时间。当前 JVM 套件只验证 499 ms / 500 ms 边界、负 age、溢出和过期事件处置分类；它没有模拟 coordinator 清理副作用，也没有制造过期物理按键事件。修复版真机对照结果见下一节。

### 修复版与省电策略对照复测

覆盖安装修复版后，原 DataStore 设置和两项已启用的无障碍服务均保留；目标服务自动重新 Bound，`START_NOT_STICKY` 映射 FGS 没有随安装自动重启。应用从可见 Activity 重新启动 FGS 后，持续通知再次出现。诊断页新增的公开应用详情入口正确落到本应用的 HyperOS 页面，且没有申请新权限或自动改系统设置。

当时 HyperOS 为本应用选择“智能限制后台运行（推荐）”。测试人员手动改为“无限制”后，在相同 A2DP 路由、起始 index `8` 下重新执行：

- 哔哩哔哩为 `topResumedActivity`，媒体会话 `active=true`、`PLAYING`，蓝牙栈 `mIsPlaying=true`，测试人员确认耳机可听见声音；
- 手机音量加 `DOWN` 时间为 `00:34:42.241`，应用在 `00:34:42.398` 调用 `setStreamVolume(index=9)`，约 157 ms；
- `AudioService` 只记录应用的 `8 → 9` 和对应 `setAvrcpVolume`，没有系统默认 `+10/+20`；
- 随后把映射器 Activity 切回前台，音量仍为 `9`，没有迟到副本或第二次应用写入；再用 0.7% 短按步长恢复为原始 `8`。

同一时刻 HyperOS 日志仍打印多条 `application accessibility dispatch key timeout`，但紧接着明确记录目标服务 `handled this event`；按实际 event time 到应用写入的时差，本次事件属于 500 ms 窗口内的新事件。该对照同时改变了应用代码和省电策略，且只有一次样本，因此只能把“当前组合在这一次真实播放中通过”作为结论，不能断言“无限制”单独构成充分修复。当前 JVM 套件仅保留上述新鲜度边界与处置分类的 2 项测试，本次也没有人为制造过期物理事件。

### 真机测试工具限制

本机调试确认，`android layout` / UiAutomator 会在该 HyperOS 设备上短暂解绑再重绑已启用的无障碍服务，因此不能用它观察实体按键测试过程；本轮改用普通截图、`dumpsys` 和日志进行只读核验。该现象属于测试工具干扰，不计为应用服务断连。

## 2026-08-22 完整模拟器 E2E

本记录随本次 E2E 提交保存。测试输入为父提交 `29d9b27` 加本次测试代码、宿主脚本和文档改动。终审后又把 fail-open 改为最新事件时间戳比较、让任何清理失败都阻断脚本，并把 UI dump 移到目标无障碍服务启用前；最终脚本在 API 36/37 再次通过。执行机为 Windows，使用项目隔离的 Studio JBR 25.0.2、Gradle 9.5.0、Android Platform Tools 37.0.1 和 Emulator 37.1.11。

当前 `adb devices -l` 只列出 `emulator-5554`，没有小米真机或蓝牙耳机。因此本节所说“完整 E2E”严格指仓库可自动执行的 AOSP 模拟器链路，不包含 OEM 或真实蓝牙硬件验收。

### 总结果

| 项目 | 结果 |
|---|---|
| 干净构建 | `clean` 后 150/150 个 Gradle task 成功 |
| JVM 单元测试 | 35/35 通过，0 failure、0 error、0 skipped |
| Debug lint | 0 blocking；3 条工具/依赖有新版本提示 |
| Release lint | 0 blocking；3 条工具/依赖有新版本提示 |
| Android instrumentation / API 28 | 3/3 通过，0 skipped、0 failed |
| Android instrumentation / API 36 | 3/3 通过，0 skipped、0 failed |
| Android instrumentation / API 37 | 3/3 通过，0 skipped、0 failed |
| 真实系统按键宿主 E2E / API 28、36、37 | 三档均通过：`5 → 11 → 5`、通知停止、系统 fail-open |
| 签名 R8 Release 安装烟测 | API 28、36、37 均安装成功、MainActivity 冷启动成功、披露入口可达、无 `DEBUGGABLE` |
| Release 黑盒门禁 | APK 对齐/签名、AAB 签名、最终 Manifest 与无障碍 XML 全部通过 |

最终干净构建命令为：

```powershell
. .\scripts\android-env.ps1
.\gradlew.bat clean `
  :app:testDebugUnitTest `
  :app:lintDebug `
  :app:lintRelease `
  :app:assembleDebug `
  :app:assembleDebugAndroidTest `
  :app:assembleRelease `
  :app:bundleRelease `
  --rerun-tasks --stacktrace --no-problems-report
```

Release 构建使用一次性本地 PKCS12 测试证书；完整校验后私钥已删除。每台 AVD 依次执行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-problems-report --stacktrace
.\scripts\emulator-e2e-test.ps1 -Serial emulator-5554 -SkipBuild
```

### 模拟器矩阵

| AVD | 系统指纹 | instrumentation | evdev E2E | 签名 Release 冷启动 |
|---|---|---:|---|---|
| `VolumeMapper_API_28` | `google/sdk_gphone_x86_64/generic_x86_64:9/PSR1.180720.122/6736742:userdebug/dev-keys` | 3/3 | `5 → 11 → 5` | 1205 ms；披露入口滚动后可见 |
| `VolumeMapper_API_36` | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` | 3/3 | `5 → 11 → 5` | 1467 ms |
| `VolumeMapper_API_37` | `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys` | 3/3 | `5 → 11 → 5` | 758 ms |

API 28 首次 Release UI 断言使用了旧系统不可靠的 `uiautomator dump /dev/tty`，且没有滚动长页面，因此虽然 APK 安装和 Activity 启动成功，自动断言没有看到屏幕外的“查看并同意”。文件式 UI dump 和截图确认页面已正常渲染；滚动后复验通过。宿主 E2E 随后增加有界滚动定位，并在 API 28、36、37 全部重新执行通过。

终审的通用可靠性修订不改变映射算法或应用 APK；修订后的宿主脚本又在 API 36/37 完整通过。按当前产品方向，API 28 保留上述已通过的基础兼容记录，但不再作为持续开发的阻断门禁，也不为它增加专用产品逻辑。

### 系统按键链路的证明范围

宿主脚本先执行 `pm clear`，其定向 Instrumentation 会真实完成 Compose 显著披露、绑定 `VolumeKeyAccessibilityService`、从可见 Activity 启动 `specialUse` FGS、检查前台通知，并从通知 action 停止控制器。普通 `connectedDebugAndroidTest` 若检测到已持久化的同意状态，则验证已同意路径而不重复显示披露。测试同时为 UIAutomator `Configurator` 与 Instrumentation `UiAutomation` 设置 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，避免测试框架压制目标服务。

`adb shell input keyevent`、`UiDevice.pressKeyCode()` 和 `UiAutomation.injectInputEvent()` 都会绕过 Accessibility input filter，不能证明 `AccessibilityService.onKeyEvent()` 收到了系统按键。宿主脚本因此临时执行 `adb root`，动态找到声明 `KEY_VOLUMEUP` 的 `/dev/input/event*`，再用 `sendevent` 产生 DOWN/SYN/UP/SYN。三档系统都观察到：

1. Activity 退到后台后，线性曲线和 40% 短按步长把媒体 index 精确映射为 `5 → 11 → 5`；
2. `dumpsys audio` 中写入调用方是 `dev.spcdts.volumemapper.debug`；
3. SystemUI 常驻通知的“停止映射”立即终止前台控制器；
4. 停止后的下一次 evdev 按键新增系统 `AudioService.adjustSuggestedStreamVolume` 记录，且不再新增应用 `setStreamVolume`，证明按键 fail-open。此时 media/ring index 可能因当前非活动流而保持不变，所以断言使用系统调用记录而不是错误地要求某个流必然变化。

### Release 产物与黑盒检查

| 产物 | 字节数 | SHA-256 |
|---|---:|---|
| `app-release.apk` | 2,310,437 | `4F7CDC6D396F60691B66B6F836297255F60D76DF545CE6D47BA40E520A737BDD` |
| `app-release.apk.idsig` | 26,646 | `0A76A9C263DA866F4E529CF211E2303944C3E08E64E28413079C1B5DAF6512E2` |
| `app-release.aab` | 3,124,368 | `C18649919FCF7329D137856C769A88BCAD619E74E5A94BEED4B22683566A776C` |

- `zipalign -c -P 16 -v 4` 与 `apksigner verify -Werr --verbose --print-certs` 均 exit 0；APK 使用 V3 签名，覆盖项目的 minSdk 28；独立 `.idsig` 未由普通 APK verify 命令计入 V4；
- `jarsigner -verify -verbose -certs` 对 AAB exit 0，并输出 `jar verified.`；
- 一次性证书 SHA-256 为 `6676078D11A1F5FB51842D1926174AAB8D98B5497BA6FC03F6362916E710AE14`，APK/AAB 相同；该证书不是生产身份，私钥已删除；
- 最终 APK 为 `dev.spcdts.volumemapper`、version `1 (0.1.0)`、minSdk 28、targetSdk 37，没有 `instrumentation` 和 `debuggable`；
- `MappingControllerService` 为 `exported=false`；无障碍服务受 `BIND_ACCESSIBILITY_SERVICE` 保护；FGS 类型和 subtype 均为 `specialUse`；
- 压缩后的两份无障碍 XML 均为 `canRequestFilterKeyEvents=true`、`canRetrieveWindowContent=false`，API 31+ 版本另有 `isAccessibilityTool=false`。

### 清理与恢复

最终停留在 API 37 AVD。只读复核结果：adbd 为 `uid=2000(shell)`，`accessibility_enabled=0`，`enabled_accessibility_services=null`，目标 Debug FGS、进程和服务均不存在。宿主脚本还在每次 `finally` 中恢复进入测试前的媒体/铃声音量、无障碍服务列表和 adbd 身份，并收起通知面板；任何清理失败都会使脚本以失败结束。Debug 应用数据按脚本设计会在测试开始和结束时清空，不能恢复测试前内容。一次性测试 keystore 已从 `artifacts/release-e2e/` 删除。

### 2026-08-24 最终工作树构建与真机收尾

在迟到 KeyEvent 保护、备份规则和跨 OEM 应用详情入口合入后，使用 `--rerun-tasks` 强制执行 `testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease bundleRelease`：51/51 JVM 测试通过，Debug/Release lint 均为 0 error、3 个依赖版本提醒，Debug/Release APK 与 AAB 构建成功。

| 当前工作树产物 | 字节数 | SHA-256 | 签名状态 |
|---|---:|---|---|
| `app-debug.apk` | 64,760,279 | `8F5A393491B41C1ECE531E3F7F53CD71D852BD0EB587281C3AC947F005BAA863` | Android Debug 证书 |
| `app-release-unsigned.apk` | 2,289,237 | `BA8378A871B87FF15A8BE27AEC6366B7B00B15112BF09C5FFDB16617169C90AE` | 未签名；`apksigner` 明确不通过 |
| `app-release.aab` | 3,124,210 | `9743E528484D34C86F1968174E726EF36FF453C96D7A360E3E1FB6CD19F2EB42` | 未签名；`jarsigner` 输出 `jar is unsigned` |

最终 Debug APK 在 Xiaomi 15 上再次覆盖安装成功。更新后，目标无障碍服务自动重新 Bound，设备原有的另一项无障碍服务仍保留，`START_NOT_STICKY` 映射 FGS 没有自动重启；A2DP 恢复并保持原始 `8/150`，扬声器保存值仍为 `62/150`。最终 APK 的公开应用详情入口再次正确打开目标应用页面；随后执行一次“启动映射 → FGS/持续通知出现 → 停止映射 → FGS/活动通知消失”烟测，音量未改变，无障碍服务保持 Bound 且控制器停止。

对当前未签名 Release APK 的 `aapt2` 黑盒检查确认：合并 Manifest 同时引用 `fullBackupContent` 与 `dataExtractionRules`；资源收缩后的两份 XML 仍分别排除 `datastore/volume_mapper.preferences_pb`，API 31+ 规则同时覆盖 cloud backup 和 device transfer。生产签名后仍需对最终上传产物重复这一检查。

`play-policy-insights` 自动审计在 Data Safety、账号和受限权限范围内给出绿色 `Compliant`，未发现数据外传或高风险权限违规；唯一建议项是必须在 Play Console 完成 `specialUse` FGS 声明并提供功能说明和演示材料。该自动报告不构成法律意见或 Play 审核保证，也不能替代 Accessibility API 声明与人工审核。

### 尚未通过的发布门槛

- 上述小米/HyperOS 单机样本已覆盖扬声器前台、桌面后台、亮屏锁屏及一副耳机的空载 A2DP 绝对音量；真实媒体播放最初因后台无障碍回调超时失败，修复版加“无限制”策略后的单次对照通过；LE Audio VCS、安全音量提示和其他设备/ROM 仍未执行；
- API 31/32、API 33–35 以及 Pixel、Samsung、OPPO/vivo/Honor 真机尚未执行；
- 来电、闹钟、相机、截图组合键、锁屏、省电和 ROM 杀后台仍需真机矩阵；
- 备份规则已同时覆盖 API 30 及以下完整备份与 API 31 及以上云备份/设备迁移，并排除包含 `disclosureAccepted` 的整个设置 DataStore；当前未签名 Release 已完成打包检查，生产签名产物仍需复核；
- Accessibility API、`specialUse` FGS、Data safety 与隐私政策仍需 Play Console 审核，自动测试不能代替政策批准。

## 2026-08-21 基线记录

提交 `d118c3f523a79f18e58cac09683271b6092736d4` 当时完成了 35/35 JVM 单测、Debug lint 0 error、API 28 与 API 37 各 2/2 instrumentation，以及未签名 Release APK/AAB 构建。该轮设备测试没有模拟系统向 AccessibilityService 分派实体按键；本次 2026-08-22 E2E 已补上真实服务、FGS、通知和 evdev 按键链路。
