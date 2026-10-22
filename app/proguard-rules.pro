# Release 版代码混淆规则
# 目前 Debug 版完全用不到这里，先留空，正式发版前再补

# 保留 Room 实体类（避免反射失败）
-keep class com.wallpaperhub.data.db.** { *; }

# 保留 WallpaperService 子类（系统通过反射加载）
-keep public class * extends android.service.wallpaper.WallpaperService { *; }
