package com.wallpaperhub.data.db

import androidx.room.TypeConverter
import com.wallpaperhub.data.model.WallpaperType

/**
 * Room 类型转换器：把枚举存成字符串。
 *
 * Room 默认只能存基本类型 + String，遇到自定义枚举需要写转换器。
 */
class Converters {
    @TypeConverter
    fun fromWallpaperType(value: WallpaperType): String = value.name

    @TypeConverter
    fun toWallpaperType(value: String): WallpaperType {
        return try {
            WallpaperType.valueOf(value)
        } catch (_: Exception) {
            WallpaperType.STATIC
        }
    }
}
