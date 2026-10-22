package com.wallpaperhub.wallpaper

import com.wallpaperhub.preview.model.CropTransform

/**
 * 渲染源接口，用于统一不同类型的壁纸（静态、视频、3D景深、着色器）的渲染逻辑。
 */
interface RenderSource {
    /** True only after the source has a drawable frame or a fully uploaded texture. */
    fun hasValidFrame(): Boolean = true

    /**
     * 初始化 OpenGL 资源（着色器编译、加载纹理等）。
     * 必须在 EGL 环境所在的渲染线程调用。
     */
    fun onInit()

    /**
     * 绘制当前帧。
     * 必须在 EGL 环境所在的渲染线程调用。
     */
    fun onDrawFrame()

    /**
     * 当渲染窗口大小（如屏幕尺寸/旋转）发生改变时调用。
     */
    fun updateSize(width: Int, height: Int)

    /**
     * 更新用户手势或设置的裁剪偏移量，范围 [-1, 1]。
     */
    fun updateOffset(offsetX: Float, offsetY: Float)

    fun updateTransform(transform: CropTransform) {
        updateOffset(transform.offsetX, transform.offsetY)
    }

    /**
     * 更新传感器数据（陀螺仪、旋转矢量等），用于 3D 景深移动。
     */
    fun updateSensor(values: FloatArray) {}

    /**
     * 释放 OpenGL 相关的硬件资源。
     * 必须在 EGL 环境所在的渲染线程调用。
     */
    fun onRelease()
}
