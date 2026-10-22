// ===========================================================
// app 模块构建脚本
// ===========================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)              // Room 注解处理用
}

android {
    namespace = "com.wallpaperhub"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wallpaperhub"
        minSdk = 24                       // Android 7.0，覆盖 ~98% 设备
        targetSdk = 35                    // Android 15，满足 Play / 国内商店最新要求
        versionCode = 8
        versionName = "0.4.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包必要的语言资源，减小 APK 体积
        resourceConfigurations += listOf("zh-rCN", "en")
    }

    buildTypes {
        debug {
            // 开发版：关闭混淆，方便调试
            isMinifyEnabled = false
        }
        release {
            // 正式版：开启资源/代码压缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ---- Kotlin / AndroidX 基础 ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)   // collectAsStateWithLifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // ---- Compose ----
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---- 视频壁纸（ExoPlayer / Media3）----
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // ---- 本地数据库（Room）----
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ---- 图片/视频缩略图加载 ----
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // ---- SAF 工具 ----
    implementation(libs.androidx.documentfile)

    // ---- 测试 ----
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
