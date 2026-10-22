# 性能基线

该模块用于执行计划中的 M0 基线：启动耗时、壁纸库首屏帧耗时，以及后续可扩展的 Apply/首帧 trace。运行前需要连接 Android 设备或模拟器：

```text
gradlew.bat :benchmark:connectedCheck
```

壁纸服务中的 `wallpaper.command.submit`、`wallpaper.queue.resolve` 和 `wallpaper.render.draw` trace slice 可在 Perfetto / System Trace 中串联检查。宏基准数字必须在目标设备上采集，不在开发机上臆测。
