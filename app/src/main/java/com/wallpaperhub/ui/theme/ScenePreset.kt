package com.wallpaperhub.ui.theme

/**
 * Visual worlds behind WallpaperHub chrome. Scene colors are intentionally separate from media
 * pixels and from semantic UI status colors. A scene may repaint the canvas, but never a photo or
 * video frame.
 */
enum class ScenePreset(
    val id: String,
    val title: String,
    val summary: String,
) {
    EVENT_HORIZON(
        "event_horizon",
        "Event Horizon",
        "黑洞、吸积盘、星尘与引力场",
    ),
    ABYSS(
        "abyss",
        "Abyss",
        "深海体积光、浮游粒子与水下纵深",
    ),
    SOLAR_CROWN(
        "solar_crown",
        "Solar Crown",
        "太阳边缘、日冕、耀斑与太阳风",
    ),
    WHITE_DWARF(
        "white_dwarf",
        "White Dwarf",
        "蓝白恒星、冷色光环与高速星尘",
    ),
    DESERT_ECLIPSE(
        "desert_eclipse",
        "Desert Eclipse",
        "夜色沙丘、日食、风沙与热浪",
    ),
    NEBULA_RIFT(
        "nebula_rift",
        "Nebula Rift",
        "紫蓝星云裂隙、能量雾与星尘",
    );

    /** Apply only scene-space colors. Chrome / semantic roles remain untouched. */
    fun applyScenePalette(profile: ThemeProfile): ThemeProfile = when (this) {
        EVENT_HORIZON -> profile.copy(
            sceneId = id,
            backgroundArgb = 0xFF03060D.toInt(),
            backgroundMidArgb = 0xFF0B1020.toInt(),
            backgroundBottomArgb = 0xFF02070C.toInt(),
            gradientAArgb = 0xFFFFA24A.toInt(),
            gradientBArgb = 0xFF5B8CFF.toInt(),
            gradientCArgb = 0xFF824DFF.toInt(),
            contourArgb = 0xFF9DDCFF.toInt(),
        )
        ABYSS -> profile.copy(
            sceneId = id,
            backgroundArgb = 0xFF011016.toInt(),
            backgroundMidArgb = 0xFF032C35.toInt(),
            backgroundBottomArgb = 0xFF020A12.toInt(),
            gradientAArgb = 0xFF22E7D6.toInt(),
            gradientBArgb = 0xFF0B8FAE.toInt(),
            gradientCArgb = 0xFF397BFF.toInt(),
            contourArgb = 0xFF8DFFF7.toInt(),
        )
        SOLAR_CROWN -> profile.copy(
            sceneId = id,
            backgroundArgb = 0xFF080705.toInt(),
            backgroundMidArgb = 0xFF1A1007.toInt(),
            backgroundBottomArgb = 0xFF050507.toInt(),
            gradientAArgb = 0xFFFFC84C.toInt(),
            gradientBArgb = 0xFFFF7A2E.toInt(),
            gradientCArgb = 0xFFFFE8A0.toInt(),
            contourArgb = 0xFFFFD77A.toInt(),
        )
        WHITE_DWARF -> profile.copy(
            sceneId = id,
            backgroundArgb = 0xFF030812.toInt(),
            backgroundMidArgb = 0xFF0A1830.toInt(),
            backgroundBottomArgb = 0xFF02060F.toInt(),
            gradientAArgb = 0xFFEAF8FF.toInt(),
            gradientBArgb = 0xFF75C7FF.toInt(),
            gradientCArgb = 0xFF6B72FF.toInt(),
            contourArgb = 0xFFD6F2FF.toInt(),
        )
        DESERT_ECLIPSE -> profile.copy(
            sceneId = id,
            backgroundArgb = 0xFF130B09.toInt(),
            backgroundMidArgb = 0xFF3A1E12.toInt(),
            backgroundBottomArgb = 0xFF0B0909.toInt(),
            gradientAArgb = 0xFFFFB15B.toInt(),
            gradientBArgb = 0xFFC96A39.toInt(),
            gradientCArgb = 0xFF6E4B60.toInt(),
            contourArgb = 0xFFFFD09B.toInt(),
        )
        NEBULA_RIFT -> profile.copy(
            sceneId = id,
            backgroundArgb = 0xFF080611.toInt(),
            backgroundMidArgb = 0xFF241038.toInt(),
            backgroundBottomArgb = 0xFF060612.toInt(),
            gradientAArgb = 0xFFB968FF.toInt(),
            gradientBArgb = 0xFF5B78FF.toInt(),
            gradientCArgb = 0xFFFF58C7.toInt(),
            contourArgb = 0xFFD5B7FF.toInt(),
        )
    }

    companion object {
        fun fromId(id: String?): ScenePreset = entries.firstOrNull { it.id == id } ?: EVENT_HORIZON
    }
}
