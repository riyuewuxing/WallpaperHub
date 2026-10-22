package com.wallpaperhub.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 缓存的当前天气信息表。
 */
@Entity(tableName = "weather_info")
data class WeatherInfoEntity(
    @PrimaryKey
    val id: String = "current",
    val condition: String,
    val tempC: String,
    val updatedAt: Long
)
