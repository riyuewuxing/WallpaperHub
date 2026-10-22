// ===========================================================
// 根 build.gradle.kts
// 这里只声明插件，不应用（apply false），让子模块决定是否启用
// ===========================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
