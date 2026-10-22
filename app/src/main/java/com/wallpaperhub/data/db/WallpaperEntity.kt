package com.wallpaperhub.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.preview.model.MediaScaleMode

/**
 * Room 表实体：一条记录 = 一个壁纸资源。
 *
 * ⚠️ 设计要点：
 * uri 可以是外部文件的 SAF 持久化 URI（content://...），也可以是复制到
 * App 专属目录后的本地副本 URI（file://...）。
 * 索引导入省空间；复制导入更适合原文件后续会移动到隐私空间的场景。
 */
@Entity(
    tableName = "wallpapers",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["type"]),
        Index(value = ["isFavorite"]),
        Index(value = ["inPlaylist", "playlistOrder"]),
        Index(value = ["inPlaylist", "playlistBaseOrder"]),
        Index(value = ["addedAt"]),
        Index(value = ["type", "addedAt"]),
        Index(value = ["isFavorite", "addedAt"])
    ]
)
data class WallpaperEntity(
    /** UUID 字符串，导入时生成 */
    @PrimaryKey
    val id: String,

    /** 资源 URI 字符串：content:// 表示外部索引，file:// 表示 App 本地副本 */
    val uri: String,

    /** 类型：静态 / 视频 */
    val type: WallpaperType,

    /** 用户可见名称（默认从 URI 解析的文件名，也可改名） */
    val name: String,

    /** 导入时间戳，毫秒 */
    val addedAt: Long,

    /** 是否收藏 */
    val isFavorite: Boolean = false,

    /** 是否已加入播放列表 */
    val inPlaylist: Boolean = false,

    /**
     * 当前“实际播放轮次”的顺序。顺序模式下它是基准顺序从当前项开始的旋转；
     * 洗牌模式下它是一轮已经物化的随机顺序。WallpaperService 与轮播页只消费它。
     */
    val playlistOrder: Long = 0L,

    /**
     * 稳定的顺序播放基准。切换到洗牌绝不能改写它；关闭洗牌时靠它恢复原始顺序。
     * 0 表示旧数据/新加入项尚未建立显式基准，查询时回退到图库默认的 addedAt DESC。
     */
    val playlistBaseOrder: Long = 0L,

    /** 用户分组标签（阶段四启用） */
    val groupTag: String? = null,

    /** 壁纸宽度（像素），图片/视频宽高，可空 */
    val width: Int? = null,

    /** 壁纸高度（像素），图片/视频宽高，可空 */
    val height: Int? = null,

    /** 视频时长（毫秒），静态图片为 null */
    val duration: Long? = null,

    val cropScale: Float = 1f,
    val cropOffsetX: Float = 0f,
    val cropOffsetY: Float = 0f,
    val cropScaleMode: String = MediaScaleMode.FILL.persistedValue,
    val cropRevision: Long = 0L
)
