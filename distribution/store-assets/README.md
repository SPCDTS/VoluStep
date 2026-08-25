# VoluStep 商店素材

本目录保存可追溯的商店视觉源稿和对应的机械导出文件。

## 文件

- `volustep-play-icon.svg`：Google Play 商店图标源稿，画布为 512 × 512。
- `volustep-play-icon.png`：Google Play 上传文件，512 × 512、8 位 RGBA PNG。
- `volustep-feature-graphic.svg`：Google Play Feature Graphic 源稿，画布为 1024 × 500。
- `volustep-feature-graphic.png`：Google Play 上传文件，1024 × 500、8 位 RGB PNG，无 Alpha 通道。
- `screenshots/zh-CN/`：简体中文主界面与应用内隐私说明，均为 1080 × 2400 PNG。
- `screenshots/en-US/`：英文主界面与应用内隐私说明，均为 1080 × 2400 PNG。

## 设计溯源

图标延续应用当前启动图标的四节点上升折线和绿色选中节点。颜色直接取自应用主题：品牌绿 `#1DB954`、浅色主色 `#15803D`、背景 `#121212`、表面 `#181818`、深色描边 `#353535`、次级文字 `#B3B3B3` 和浅色中性色 `#F6F6F6`。

素材不包含生成式图像、照片、第三方图标或其他外部版权素材。SVG 内的 `metadata` 记录了对应代码来源、几何关系、色值和制作日期。

## 导出与验收

PNG 由本机 Chromium 对 SVG 进行等尺寸无缩放渲染。Feature Graphic 随后机械展平为 24 位 RGB。验收检查 PNG 的 IHDR、像素尺寸、位深、颜色类型和文件大小，并对两张成品图进行视觉检查。

手机截图于 2026-08-25 从 API 37 的 `VolumeMapper_API_37` AVD 直接截取，分别使用 `zh-CN` 与 `en-US` 应用语言。截图对应当前已通过测试的源码和 Debug 签名包，不包含调试覆盖层或虚构设备状态；它们是商店资料草稿，不构成生产签名候选包的验证证据。生产 AAB 进入内部测试轨道后，应逐像素复核关键界面；如正式构建、系统栏或文案有任何差异，必须重新截图。

Debug 构建不会注入最终隐私政策 URL，因此隐私截图不显示正式包中的“完整政策 / Full policy”按钮。发布者提供并部署最终 HTTPS 地址后，必须用签名候选包重新拍摄隐私截图，确认按钮可见且能在未登录浏览器中打开正确页面，再替换本目录及 Fastlane 副本。

供 Fastlane/Supply 使用的机械副本位于 `fastlane/metadata/android/<locale>/images/`：`icon.png`、`featureGraphic.png` 与 `phoneScreenshots/`。更新本目录源文件后必须同步副本并核对 SHA-256，避免上传旧素材。
