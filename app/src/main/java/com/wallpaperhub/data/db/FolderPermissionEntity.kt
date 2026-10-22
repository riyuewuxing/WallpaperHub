package com.wallpaperhub.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 追踪导入的文件夹权限的实体类。
 * 用于实现 LRU 驱逐策略以避免超出 Android 的 512 SAF 限制。
 */
@Entity(tableName = "folder_permissions")
data class FolderPermissionEntity(
    @PrimaryKey
    val uri: String,
    val lastAccessedAt: Long
)
