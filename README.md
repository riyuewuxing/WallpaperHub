# WallpaperHub · 手机壁纸管家

[![Android CI](https://github.com/riyuewuxing/WallpaperHub/actions/workflows/android-ci.yml/badge.svg)](https://github.com/riyuewuxing/WallpaperHub/actions/workflows/android-ci.yml)
[![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/platform-Android%207%2B-informational)

**一个面向 Android 的本地壁纸管理器：在同一个应用中管理静态图片和视频动态壁纸。**

**WallpaperHub** is an open-source Android wallpaper manager for still images and video live wallpapers. It combines a local media library, playlist-based rotation, wallpaper previews, and per-item framing in one native Android application.

## 为什么开发它？ | Why it exists

我最初想找一款同时管理静态壁纸与视频动态壁纸、适合存放大量个人素材的手机软件，但现有工具的类型区分和媒体数量限制不符合自己的使用方式，于是开始开发 WallpaperHub，并一直在自己的手机上使用和迭代。

The project began with a practical need: managing still and video wallpapers together, without an arbitrary app-level item cap. It has evolved through personal use, testing, and iterative improvements.

## 当前功能 | Features

| 功能 | 描述 |
| --- | --- |
| 🖼️ 图片 + 视频图库 | 导入、查看、搜索、筛选、收藏及批量管理本地媒体 |
| 🎬 视频动态壁纸 | 基于 Android `WallpaperService` 和 AndroidX Media3 的视频壁纸播放 |
| 🔁 轮播 | 多种播放顺序、切换间隔及转场效果 |
| ✂️ 构图编辑 | 在壁纸应用前调整取景位置、平移和缩放 |
| 🧹 媒体整理 | 完全重复、视觉相似和媒体画质检查相关功能 |
| 🎨 主题与场景 | 多种应用视觉主题和场景设置 |
| 🧪 代码验证 | Kotlin 单元测试、Android Lint、GitHub Actions Debug 构建及 Benchmark 测试包 |

**关于图库数量**：应用没有为了模仿其他软件而设置固定的素材数量上限，但可处理数量仍取决于设备的内存、存储与性能。这不代表无限容量或已经验证所有图库规模。

**功能边界**：仓库保留了部分未启用的历史实验代码，例如 `WifiSyncService.kt`。当前 AndroidManifest 未注册该服务，因此 **PC/Wi-Fi 同步不是这个公开版本的已发布功能**。

## 技术栈 | Tech stack

- Kotlin、Jetpack Compose、Material 3
- Android `WallpaperService`、AndroidX Media3
- Room、DataStore、Storage Access Framework、Coil
- Android SDK：`minSdk 24`、`targetSdk 35`、`compileSdk 36`
- JDK 17、Gradle Wrapper、GitHub Actions

## 构建和运行 | Build from source

需要 Android Studio、JDK 17 和 Android SDK API 36。打开仓库根目录，等待 Gradle 同步，然后运行：

```powershell
# Windows
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

```bash
# macOS / Linux
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

调试 APK 通常位于 `app/build/outputs/apk/debug/app-debug.apk`。只使用可信开发环境与系统授权机制，不需要关闭 Android 的安全防护。

> **公开源码快照：** 该仓库从独立维护的私有研发仓库中提取必要代码、测试和构建文件，以新的 Git 历史公开。原始研发提交历史、内部诊断截图、演示视频和内部计划档案没有迁入。

> **图标说明：** 当前公开构建采用临时矢量启动图标，与私有开发版本中的原始 PNG 图标不同；后续可在素材权属确认及文件传输条件具备时恢复。

## 当前验证状态 | CI & releases

- 开发版本：`0.4.4`（见 `app/build.gradle.kts`），**尚未发布正式签名 Release**。
- 公开仓库已通过一次 [Android CI](https://github.com/riyuewuxing/WallpaperHub/actions/runs/39728107741)：Kotlin 编译、单元测试、Android Lint、Debug APK 与 Benchmark 测试包构建成功。
- CI 成功证明相应提交能在指定构建环境中通过检查，**不等于所有 Android 型号的兼容性、完整性能测试或正式商店发布认证**。
- GitHub Actions 生成的测试 APK 可在相关工作流的 Artifacts 区域查看；它不是正式 Release。

## 使用反馈与贡献 | Contributing

欢迎提供可复现的 Bug 报告、改进建议与 Pull Request。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，也可以通过 [Issues](https://github.com/riyuewuxing/WallpaperHub/issues) 讨论问题。

涉及漏洞、私人照片、设备标识或日志时，请勿在公开 Issue 中发布敏感信息；参阅 [SECURITY.md](SECURITY.md)。

## License

源代码以 [MIT License](LICENSE) 开源。第三方依赖遵循其各自许可证。请不要假设任何外部照片、视频、商标或第三方媒体会自动获得与本项目代码相同的再分发授权。

---

*Built to solve a real wallpaper-management problem, maintained with a focus on usability, local media ownership, and reproducible engineering.*
