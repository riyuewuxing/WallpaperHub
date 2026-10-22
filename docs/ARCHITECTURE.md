# WallpaperHub Architecture / 架构概览

This document describes the **currently published source snapshot**. It is a map for contributors, not a guarantee that every experimental class is enabled in the shipped Android application.

## 1. Entry points / 入口

- `app/src/main/java/com/wallpaperhub/MainActivity.kt`: Android activity; configures the Compose root and app navigation.
- `ui/AppNavigation.kt`: routes between library, carousel, settings, details and preview/editor screens.
- `WallpaperHubApp.kt`: initializes shared repositories, playback coordination and the Coil image loader.
- `app/src/main/AndroidManifest.xml`: authoritative list of Android components/permissions in the public snapshot.

## 2. Media library / 本地媒体

The UI and view models interact with `data/repo/WallpaperRepository.kt`, which uses Room DAOs and Android URI/content-provider access. The media library stores metadata, favorites, grouping and per-item crop transforms. Media import and playlist membership are separate concerns: adding a library item does not silently rewrite the playback plan.

Key folders:

- `data/db/`: Room entities and DAOs.
- `data/repo/`: library operations, imports and media cleanup.
- `analysis/`: duplicate and media-quality analysis.
- `ui/library/` and `ui/settings/`: public-facing library and maintenance UI.

## 3. Playback / 壁纸播放

The wallpaper service is `wallpaper/VideoWallpaperService.kt`. Rendering and decoding are implemented in the wallpaper/engine-related files, including `GLVideoRenderer.kt`, with AndroidX Media3.

Playback state and commands are deliberately separated from UI presentation:

1. UI submits Apply/Next/Previous through `data/repo/PlaybackRepository.kt`.
2. The repository writes durable command/session state via Room.
3. `playback/engine/PlaybackOrderCoordinator.kt` owns planning and canonical playback order.
4. `playback/engine/QueueController.kt` moves through an already-materialized queue without introducing a second, hidden randomization.
5. `VideoWallpaperService` renders media and confirms state to the durable playback layer.

This separation matters for recovery after activity/process changes and for keeping the on-screen current-wallpaper status consistent with what actually rendered.

## 4. Framing and previews / 构图与预览

`preview/model/` contains crop geometry and media transform policies. Saved per-item transformations are reused by preview and wallpaper-rendering code to reduce discrepancies between what a user composes and what Android displays.

## 5. Testability / 测试

The repository has tests in `app/src/test/` and an instrumentation/benchmark module under `benchmark/`.

See [Android CI](https://github.com/riyuewuxing/WallpaperHub/actions/workflows/android-ci.yml) for the build pipeline. CI checks compile, unit tests, Lint and Debug APK/Benchmark test-package build. A green check does **not** constitute cross-device wallpaper lifecycle or performance certification.

## 6. Publication and security boundaries / 公开边界

- The public repository begins with a **new Git history**. Original private development commits, private media diagnostics and planning archives were intentionally not imported.
- `WifiSyncService.kt` is a **legacy, inactive source file** in this snapshot: it is not registered in the current Manifest and should not be advertised as an enabled PC sync feature.
- Demo wallpapers and third-party media are not automatically licensed by the software's MIT License.
- The public snapshot uses a temporary vector launcher icon instead of the earlier private image; see the README.
- Reports containing private files or exploit details should follow [SECURITY.md](../SECURITY.md).

Architecture notes should be updated when actual implementation boundaries change.
