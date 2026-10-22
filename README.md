# WallpaperHub · 手机壁纸管家

**Android wallpaper library for still images and videos** · 用一个本地应用管理静态图片、视频壁纸和轮播。

WallpaperHub 是为解决个人实际使用中的壁纸管理问题而开发的 Android 应用：既希望统一管理静态壁纸与动态视频，又希望保留较大的本地媒体库，而不是受某个固定导入数量限制。项目优先考虑本地管理、真实预览、可靠应用与后续可维护性。

**公开快照说明**：本仓库是从原私有研发仓库的已审查源码建立的独立 Git 历史，不包含原开发仓库的历史提交。开发、验收和公开发布将分别记录。\n\n> **项目状态**：开发版本 **0.4.4**（以 `app/build.gradle.kts` 为准）。目前尚未发布正式 Release，公开发布前的素材与历史隐私审查仍在进行。此说明不代表所有设备或功能均已通过验收。

## 已实现的主要功能（基于当前代码）

- **统一图库**：导入和浏览本地静态图片、视频素材；搜索、筛选、收藏、分组和批量整理。
- **壁纸应用与轮播**：Android `WallpaperService` + Media3 视频播放；顺序、随机等队列策略及转场效果。
- **独立构图预览**：对单张壁纸进行取景、平移与缩放，预览和渲染逻辑复用变换模型。
- **大图库整理**：完全重复项、相似内容、媒体画质检查相关功能；实际处理能力受设备性能和存储条件限制。
- **视觉个性化**：主题与场景设置；部分高级诊断界面只对 Debug 构建开放。

**数量说明**：项目没有把其他软件的具体数量上限作为本应用的硬编码限制；“无数量上限”不代表无限内存或已验证任意规模的图库。极大媒体库仍需在真机上测量性能。

**尚非正式能力**：源码仓库中保留部分实验或遗留实现（如 `WifiSyncService.kt`），但当前主分支的 AndroidManifest **未注册 Wi-Fi 同步服务**。请勿将其描述为现行、已发布或已验收的功能。

## 运行环境

| 项目 | 要求 |
| --- | --- |
| 开发语言 | Kotlin / Jetpack Compose |
| 构建 | Android Studio、Gradle Wrapper |
| JDK | 17 |
| Android SDK | compileSdk 36；targetSdk 35 |
| 最低 Android | API 24（Android 7.0） |
| 视频框架 | AndroidX Media3 |
| 数据库与媒体 | Room、Android Storage Access Framework |

## 从源码构建

1. 从 Android 官方渠道安装 Android Studio 和 Android SDK，安装 Android API 36 平台。
2. 使用 Android Studio 打开仓库根目录，选择 JDK 17，等待 Gradle 同步完成。
3. 在仓库根目录执行：

```bash
# macOS / Linux
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

```powershell
# Windows
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

4. 如需通过 USB 安装调试包，请按 Android 官方开发者指南启用 USB 调试，并仅授权可信计算机。**正常使用不要求关闭系统安全检查或权限监控。**

本仓库当前未提供正式签名的 Release APK。Debug APK 仅适用于开发、测试，不能代表正式发布包。若网络环境导致依赖解析失败，请检查 Gradle 日志并使用可信镜像或官方仓库，不建议全局关闭系统保护。

## 构建与验证证据

仓库包含单元测试与 Android Macrobenchmark 项目；`.github/workflows/` 保存了 CI 构建脚本。2026-08-14，提交 `d0ab5e9a382bf0f97a81262c187d4f04b9e68bfd` 的 *Final Long-Run Hardening Gate* 工作流执行成功，验证了当时配置下的编译、单元测试、Lint 和 Debug APK 构建。

CI 成功不等于全部 Android 设备经过真机测试。项目历史设计、构建与验收记录见 `docs/`，其中一些记录反映的是**旧版状态**，不能直接视作当前产品承诺。

## 隐私与安全

当前被审查提交的主应用 Manifest 未请求互联网权限，也未注册旧版 Wi-Fi 同步服务。请使用系统媒体授权界面导入个人素材，不要把真实私人照片、配对信息或调试日志提交到公共 Issue。安全问题请参阅 [SECURITY.md](SECURITY.md)。

## 贡献与许可证

欢迎后续报告可复现的问题和提出功能建议。公开源码已经迁移，用户已选择并在准备分支加入 **[MIT License](LICENSE)**。此授权以你依法有权许可的内容为限；截图、示例视频及第三方素材的权属仍待核验，公开前不得默认这些素材都有可再分发许可。截图、示例视频和其他素材可能有独立权属，需要分别确认后才能开放再分发。

## 开发背景

最初只是希望在自己的 Android 手机上同时使用静态与视频壁纸，并更方便地管理大量素材。项目在真实使用和多轮反馈中逐渐增加了轮播、构图、性能稳定性与媒体整理能力。后续开发以修复真实问题和完善验证证据为优先，而非为展示功能数量而增加复杂度。
