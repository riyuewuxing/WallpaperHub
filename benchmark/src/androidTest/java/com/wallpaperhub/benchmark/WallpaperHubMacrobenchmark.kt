package com.wallpaperhub.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallpaperHubMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupAndLibraryFrameTiming() = benchmarkRule.measureRepeated(
        packageName = "com.wallpaperhub",
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
        setupBlock = { device.pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("图库")), 5_000)
        val content = device.findObject(By.scrollable(true))
        if (content != null) {
            repeat(5) { content.fling(Direction.DOWN) }
            repeat(3) { content.fling(Direction.UP) }
        }
        device.waitForIdle()
    }

    /** Exercises the heaviest non-media UI: Scene switching plus Theme Studio scrolling. */
    @Test
    fun themeStudioSceneFrameTiming() = benchmarkRule.measureRepeated(
        packageName = "com.wallpaperhub",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("设置")), 5_000)
            device.findObject(By.text("设置"))?.click()
            device.wait(Until.hasObject(By.text("主题与场景")), 5_000)
            device.findObject(By.text("主题与场景"))?.click()
            device.wait(Until.hasObject(By.text("Scene")), 5_000)
        },
    ) {
        device.findObject(By.text("Solar Crown"))?.click()
        device.waitForIdle()
        val content = device.findObject(By.scrollable(true))
        if (content != null) {
            repeat(3) { content.fling(Direction.DOWN) }
            repeat(2) { content.fling(Direction.UP) }
        }
        device.waitForIdle()
    }
}
