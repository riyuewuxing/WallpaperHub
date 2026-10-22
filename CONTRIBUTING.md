# Contributing to WallpaperHub

Thank you for your interest in improving WallpaperHub! / 感谢关注手机壁纸管家！

WallpaperHub is an independently maintained Android application. Small, focused fixes and clear reproduction steps are especially helpful.

## Report an issue / 反馈问题

Use [GitHub Issues](https://github.com/riyuewuxing/WallpaperHub/issues) to report reproducible bugs or propose improvements.

Before reporting, search for an existing issue. For bugs, include:

- WallpaperHub version or commit SHA (for example, `0.4.4`);
- Android version and phone model;
- minimal reproduction steps, expected behavior, and actual behavior;
- whether the media is a still image or video, and whether the issue happens with multiple items;
- sanitized logs or screenshots **only when needed**.

**Privacy:** Do not publish private wallpapers, unredacted file paths, credential material, personal contact information, device IDs, raw Logcat dumps, or pairing PINs. Use non-sensitive test media. See [SECURITY.md](SECURITY.md) for vulnerability reporting.

## Local development / 本地开发

1. Install Android Studio and Android API 36; select JDK 17.
2. Fork the repository, create a descriptive branch, and keep your changes focused.
3. Run the unit tests and compile the Debug app:

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
```

4. For Android-specific changes, test on a device/emulator when possible. Describe what you verified and what remains untested.

## Pull requests

- Explain the user-visible problem your change solves and its scope.
- Add or adjust tests for behavior changes.
- Avoid coupling unrelated UI, storage, playback, and renderer changes in one PR.
- Preserve the local-media-first behavior and Android permission boundaries.
- Do not include demo videos, copyrighted images, private screenshots, generated APKs, or large binary artifacts without confirmed redistribution rights.
- Keep the current Gradle/Android build working.

A successful CI check is necessary for many changes, but it is not a substitute for real-device testing where relevant.

## Licensing

By submitting a contribution, you agree that your contribution is licensed under the project's [MIT License](LICENSE). Only submit content you have the right to contribute. Dependencies and bundled assets may have separate licenses.

Maintainer review and merge are discretionary; this project does not promise a response time or roadmap commitment.
