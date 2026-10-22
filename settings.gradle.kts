// ===========================================================
// Gradle 项目总配置文件
// 作用：声明项目名、子模块、以及全局的依赖仓库
// ===========================================================

pluginManagement {
    repositories {
        // ---- 国内镜像，加速下载 ----
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // ---- 官方仓库作为兜底 ----
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // -----------------------------------------------------------
    // 国内网络下，plugins.gradle.org 经常超时，导致 KSP 等
    // "只发布在 Gradle Plugin Portal" 的插件无法解析。
    // 这里把插件 ID 显式映射到 Maven 坐标，走阿里云/Google 镜像。
    // -----------------------------------------------------------
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                // KSP：插件 marker 仅在 plugin portal，把它指向真正的 Maven 坐标
                "com.google.devtools.ksp" -> useModule(
                    "com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    // 强制只使用 settings 里声明的仓库，避免 module 里又写一遍
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ---- 国内镜像 ----
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        // ---- 官方兜底 ----
        google()
        mavenCentral()
    }
}

// 项目名：会显示在 Android Studio 标题栏
rootProject.name = "WallpaperHub"

// 包含一个 app 子模块
include(":app")
include(":benchmark")
