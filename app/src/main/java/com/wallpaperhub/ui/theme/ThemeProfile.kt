package com.wallpaperhub.ui.theme

data class ThemeProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val presetId: String = ThemePreset.AQUA_NOIR.id,
    val sceneId: String = ScenePreset.EVENT_HORIZON.id,
    val accentArgb: Int,
    val backgroundArgb: Int,
    val backgroundMidArgb: Int,
    val backgroundBottomArgb: Int,
    val gradientAArgb: Int,
    val gradientBArgb: Int,
    val gradientCArgb: Int,
    val contourArgb: Int,
    val surfaceArgb: Int,
    val surfaceHighArgb: Int,
    val textPrimaryArgb: Int,
    val textSecondaryArgb: Int,
    val navigationArgb: Int,
    val primaryButtonArgb: Int,
    val primaryButtonContentArgb: Int,
    val secondaryButtonArgb: Int,
    val selectedArgb: Int,
    val strokeArgb: Int,
    val focusArgb: Int,
    val glowArgb: Int,
    val rippleArgb: Int,
    val particlePrimaryArgb: Int,
    val particleSecondaryArgb: Int,
    val successArgb: Int,
    val warningArgb: Int,
    val errorArgb: Int,
    val backgroundMotionLevel: Int = 2,
    val ambientParticleLevel: Int = 2,
    val interactionEffectLevel: Int = 2,
    val hapticLevel: Int = 1,
    val performanceMode: Int = 1,
    val auroraIntensityLevel: Int = 2,
    val contourLevel: Int = 1,
    val grainLevel: Int = 1,
    val vignetteLevel: Int = 1,
    val particleSpeedLevel: Int = 2,
    val sceneSpeedLevel: Int = 2,
    val parallaxLevel: Int = 1,
    val eventLevel: Int = 1,
) {
    fun color(role: ThemeColorRole): Int = when (role) {
        ThemeColorRole.ACCENT -> accentArgb
        ThemeColorRole.BACKGROUND -> backgroundArgb
        ThemeColorRole.BACKGROUND_MID -> backgroundMidArgb
        ThemeColorRole.BACKGROUND_BOTTOM -> backgroundBottomArgb
        ThemeColorRole.GRADIENT_A -> gradientAArgb
        ThemeColorRole.GRADIENT_B -> gradientBArgb
        ThemeColorRole.GRADIENT_C -> gradientCArgb
        ThemeColorRole.CONTOUR -> contourArgb
        ThemeColorRole.SURFACE -> surfaceArgb
        ThemeColorRole.SURFACE_HIGH -> surfaceHighArgb
        ThemeColorRole.TEXT_PRIMARY -> textPrimaryArgb
        ThemeColorRole.TEXT_SECONDARY -> textSecondaryArgb
        ThemeColorRole.NAVIGATION -> navigationArgb
        ThemeColorRole.PRIMARY_BUTTON -> primaryButtonArgb
        ThemeColorRole.PRIMARY_BUTTON_CONTENT -> primaryButtonContentArgb
        ThemeColorRole.SECONDARY_BUTTON -> secondaryButtonArgb
        ThemeColorRole.SELECTED -> selectedArgb
        ThemeColorRole.STROKE -> strokeArgb
        ThemeColorRole.FOCUS -> focusArgb
        ThemeColorRole.GLOW -> glowArgb
        ThemeColorRole.RIPPLE -> rippleArgb
        ThemeColorRole.PARTICLE_PRIMARY -> particlePrimaryArgb
        ThemeColorRole.PARTICLE_SECONDARY -> particleSecondaryArgb
        ThemeColorRole.SUCCESS -> successArgb
        ThemeColorRole.WARNING -> warningArgb
        ThemeColorRole.ERROR -> errorArgb
    }

    fun withColor(role: ThemeColorRole, argb: Int): ThemeProfile = when (role) {
        ThemeColorRole.ACCENT -> copy(accentArgb = argb)
        ThemeColorRole.BACKGROUND -> copy(backgroundArgb = argb)
        ThemeColorRole.BACKGROUND_MID -> copy(backgroundMidArgb = argb)
        ThemeColorRole.BACKGROUND_BOTTOM -> copy(backgroundBottomArgb = argb)
        ThemeColorRole.GRADIENT_A -> copy(gradientAArgb = argb)
        ThemeColorRole.GRADIENT_B -> copy(gradientBArgb = argb)
        ThemeColorRole.GRADIENT_C -> copy(gradientCArgb = argb)
        ThemeColorRole.CONTOUR -> copy(contourArgb = argb)
        ThemeColorRole.SURFACE -> copy(surfaceArgb = argb)
        ThemeColorRole.SURFACE_HIGH -> copy(surfaceHighArgb = argb)
        ThemeColorRole.TEXT_PRIMARY -> copy(textPrimaryArgb = argb)
        ThemeColorRole.TEXT_SECONDARY -> copy(textSecondaryArgb = argb)
        ThemeColorRole.NAVIGATION -> copy(navigationArgb = argb)
        ThemeColorRole.PRIMARY_BUTTON -> copy(primaryButtonArgb = argb)
        ThemeColorRole.PRIMARY_BUTTON_CONTENT -> copy(primaryButtonContentArgb = argb)
        ThemeColorRole.SECONDARY_BUTTON -> copy(secondaryButtonArgb = argb)
        ThemeColorRole.SELECTED -> copy(selectedArgb = argb)
        ThemeColorRole.STROKE -> copy(strokeArgb = argb)
        ThemeColorRole.FOCUS -> copy(focusArgb = argb)
        ThemeColorRole.GLOW -> copy(glowArgb = argb)
        ThemeColorRole.RIPPLE -> copy(rippleArgb = argb)
        ThemeColorRole.PARTICLE_PRIMARY -> copy(particlePrimaryArgb = argb)
        ThemeColorRole.PARTICLE_SECONDARY -> copy(particleSecondaryArgb = argb)
        ThemeColorRole.SUCCESS -> copy(successArgb = argb)
        ThemeColorRole.WARNING -> copy(warningArgb = argb)
        ThemeColorRole.ERROR -> copy(errorArgb = argb)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
        fun default(): ThemeProfile = ThemePreset.AQUA_NOIR.profile()
    }
}

enum class ThemeColorRole(val key: String, val title: String, val summary: String) {
    ACCENT("accent", "主强调色", "按钮、焦点与主要交互"),
    BACKGROUND("background", "场景顶部", "Scene 最底层顶部色"),
    BACKGROUND_MID("background_mid", "场景中部", "Scene 主体空间色"),
    BACKGROUND_BOTTOM("background_bottom", "场景底部", "Scene 纵深收束色"),
    GRADIENT_A("gradient_a", "场景能量 A", "场景主发光 / 主能量色"),
    GRADIENT_B("gradient_b", "场景能量 B", "场景第二能量色"),
    GRADIENT_C("gradient_c", "场景能量 C", "场景远景 / 深度色"),
    CONTOUR("contour", "空间流线", "Scene 轮廓、轨迹与体积光"),
    SURFACE("surface", "基础表面", "卡片、面板与普通容器"),
    SURFACE_HIGH("surface_high", "高层表面", "浮层、菜单与强调容器"),
    TEXT_PRIMARY("text_primary", "主要文字", "标题与核心信息；运行时强制可读"),
    TEXT_SECONDARY("text_secondary", "次要文字", "说明、元数据与非激活状态；运行时强制可读"),
    NAVIGATION("navigation", "导航底色", "底部 Dock 与导航 Chrome"),
    PRIMARY_BUTTON("primary_button", "主按钮底色", "主要操作按钮"),
    PRIMARY_BUTTON_CONTENT("primary_button_content", "主按钮内容", "主按钮文字与图标"),
    SECONDARY_BUTTON("secondary_button", "次按钮底色", "次要操作与选择容器"),
    SELECTED("selected", "选中状态", "Tab、选中边框与当前项目"),
    STROKE("stroke", "描边", "卡片、Dock 与玻璃边缘"),
    FOCUS("focus", "焦点", "输入框、编辑工具与键盘焦点"),
    GLOW("glow", "辉光", "按钮和选中状态的外部光晕"),
    RIPPLE("ripple", "点击能量波", "点击后从触点扩散的波纹"),
    PARTICLE_PRIMARY("particle_primary", "交互主粒子", "点击反馈粒子的主要颜色"),
    PARTICLE_SECONDARY("particle_secondary", "交互辅粒子", "点击反馈粒子的辅助颜色"),
    SUCCESS("success", "成功", "应用成功等安全语义"),
    WARNING("warning", "警告", "需要注意但非破坏性的状态"),
    ERROR("error", "错误 / 删除", "错误、失败与破坏性操作"),
}

enum class ThemePreset(val id: String, val title: String) {
    AQUA_NOIR("aqua_noir", "Aqua Chrome"),
    VIOLET_NEBULA("violet_nebula", "Violet Chrome"),
    ROSE_NOIR("rose_noir", "Rose Chrome"),
    EMERALD_NIGHT("emerald_night", "Emerald Chrome"),
    SOLAR_GOLD("solar_gold", "Solar Chrome"),
    MONO_INK("mono_ink", "Mono Chrome"),
    CUSTOM("custom", "自定义");

    fun profile(): ThemeProfile = when (this) {
        AQUA_NOIR -> base(
            id, ScenePreset.EVENT_HORIZON,
            0xFF4FF4E3.toInt(), 0xC70A171A.toInt(), 0xE5162529.toInt(), 0xFFF4FBFA.toInt(),
            0xFFB5CDCC.toInt(), 0xDB08171A.toInt(), 0xFF57F4E5.toInt(), 0xFF031614.toInt(),
            0xD91A3235.toInt(), 0xFF8BFFF5.toInt(), 0x704FF4E3.toInt(), 0xFFB5FFF9.toInt(),
            0xFF22E2D3.toInt(), 0xFFB7FFF9.toInt(), 0xFF8AFFF5.toInt(), 0xFF6CB5FF.toInt(),
        )
        VIOLET_NEBULA -> base(
            id, ScenePreset.NEBULA_RIFT,
            0xFFC1A0FF.toInt(), 0xC7141020.toInt(), 0xE5231B34.toInt(), 0xFFFAF7FF.toInt(),
            0xFFC5B9D8.toInt(), 0xDB130F20.toInt(), 0xFFC4A5FF.toInt(), 0xFF180A2B.toInt(),
            0xD92C2340.toInt(), 0xFFE0D0FF.toInt(), 0x70B894FF.toInt(), 0xFFE7D9FF.toInt(),
            0xFF9367FF.toInt(), 0xFFE3D4FF.toInt(), 0xFFE2D5FF.toInt(), 0xFF72C9FF.toInt(),
        )
        ROSE_NOIR -> base(
            id, ScenePreset.NEBULA_RIFT,
            0xFFFF8FC1.toInt(), 0xC7190D14.toInt(), 0xE52B1721.toInt(), 0xFFFFF7FA.toInt(),
            0xFFD3B6C3.toInt(), 0xDB190D14.toInt(), 0xFFFF94C3.toInt(), 0xFF250812.toInt(),
            0xD9321C27.toInt(), 0xFFFFB5D3.toInt(), 0x70FF86BA.toInt(), 0xFFFFCDE0.toInt(),
            0xFFFF5CA4.toInt(), 0xFFFFD0E2.toInt(), 0xFFFFBCD7.toInt(), 0xFFB89CFF.toInt(),
        )
        EMERALD_NIGHT -> base(
            id, ScenePreset.ABYSS,
            0xFF68F1B6.toInt(), 0xC70A1711.toInt(), 0xE515291F.toInt(), 0xFFF4FCF8.toInt(),
            0xFFB4CEC0.toInt(), 0xDB091711.toInt(), 0xFF6DF1B9.toInt(), 0xFF04160D.toInt(),
            0xD91A3428.toInt(), 0xFF9AFFCE.toInt(), 0x7068F1B6.toInt(), 0xFFC0FFE1.toInt(),
            0xFF33DD95.toInt(), 0xFFC0FFE1.toInt(), 0xFFA6FFD3.toInt(), 0xFF63E3EA.toInt(),
        )
        SOLAR_GOLD -> base(
            id, ScenePreset.SOLAR_CROWN,
            0xFFFFD274.toInt(), 0xC719130A.toInt(), 0xE52B2212.toInt(), 0xFFFFFAEF.toInt(),
            0xFFD4C3A1.toInt(), 0xDB19130A.toInt(), 0xFFFFD06C.toInt(), 0xFF1B1201.toInt(),
            0xD9362A16.toInt(), 0xFFFFE0A0.toInt(), 0x70FFD274.toInt(), 0xFFFFE9B8.toInt(),
            0xFFFFB52D.toInt(), 0xFFFFE9B8.toInt(), 0xFFFFE5A7.toInt(), 0xFFFFA66A.toInt(),
        )
        MONO_INK -> base(
            id, ScenePreset.WHITE_DWARF,
            0xFFEAF0F4.toInt(), 0xC713171A.toInt(), 0xE51F2529.toInt(), 0xFFF7F9FA.toInt(),
            0xFFBAC2C8.toInt(), 0xDB121619.toInt(), 0xFFE9EEF2.toInt(), 0xFF111416.toInt(),
            0xD92A3136.toInt(), 0xFFFFFFFF.toInt(), 0x70D9E0E5.toInt(), 0xFFFFFFFF.toInt(),
            0xFFC8D1D7.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFBBC6CD.toInt(),
        )
        CUSTOM -> AQUA_NOIR.profile().copy(presetId = CUSTOM.id)
    }

    companion object {
        fun fromId(id: String?): ThemePreset = entries.firstOrNull { it.id == id } ?: AQUA_NOIR

        private fun base(
            id: String,
            scene: ScenePreset,
            accent: Int,
            surface: Int,
            surfaceHigh: Int,
            text: Int,
            muted: Int,
            navigation: Int,
            primaryButton: Int,
            primaryButtonContent: Int,
            secondaryButton: Int,
            selected: Int,
            stroke: Int,
            focus: Int,
            glow: Int,
            ripple: Int,
            particlePrimary: Int,
            particleSecondary: Int,
        ): ThemeProfile {
            val profile = ThemeProfile(
                presetId = id,
                sceneId = scene.id,
                accentArgb = accent,
                backgroundArgb = 0xFF03060D.toInt(),
                backgroundMidArgb = 0xFF0B1020.toInt(),
                backgroundBottomArgb = 0xFF02070C.toInt(),
                gradientAArgb = 0xFFFFA24A.toInt(),
                gradientBArgb = 0xFF5B8CFF.toInt(),
                gradientCArgb = 0xFF824DFF.toInt(),
                contourArgb = 0xFF9DDCFF.toInt(),
                surfaceArgb = surface,
                surfaceHighArgb = surfaceHigh,
                textPrimaryArgb = text,
                textSecondaryArgb = muted,
                navigationArgb = navigation,
                primaryButtonArgb = primaryButton,
                primaryButtonContentArgb = primaryButtonContent,
                secondaryButtonArgb = secondaryButton,
                selectedArgb = selected,
                strokeArgb = stroke,
                focusArgb = focus,
                glowArgb = glow,
                rippleArgb = ripple,
                particlePrimaryArgb = particlePrimary,
                particleSecondaryArgb = particleSecondary,
                successArgb = 0xFF63E6A4.toInt(),
                warningArgb = 0xFFFFC85A.toInt(),
                errorArgb = 0xFFFF6E7B.toInt(),
            )
            return scene.applyScenePalette(profile)
        }
    }
}

object ThemeLevels {
    val motion = listOf(0 to "关闭", 1 to "轻", 2 to "标准", 3 to "丰富")
    val particles = listOf(0 to "关闭", 1 to "少", 2 to "标准", 3 to "多")
    val interaction = listOf(0 to "关闭", 1 to "轻", 2 to "标准", 3 to "丰富")
    val haptics = listOf(0 to "关闭", 1 to "轻", 2 to "标准")
    val performance = listOf(0 to "节能", 1 to "平衡", 2 to "极致")
    val intensity = listOf(0 to "关闭", 1 to "轻", 2 to "标准", 3 to "丰富")
    val speed = listOf(0 to "慢", 1 to "舒缓", 2 to "标准", 3 to "活跃")
    val parallax = listOf(0 to "关闭", 1 to "轻", 2 to "标准", 3 to "明显")
    val events = listOf(0 to "关闭", 1 to "偶尔", 2 to "标准", 3 to "丰富")
}
