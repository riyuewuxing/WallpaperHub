package com.wallpaperhub.ui.library

/** Persisted gallery layouts. The list intentionally omits artwork for fast text-only scanning. */
enum class GalleryViewMode(
    val persistedValue: String,
    val label: String,
) {
    LIST("list", "列表"),
    LARGE_GRID("large_grid", "大图"),
    COMFORTABLE_GRID("comfortable_grid", "双列"),
    COMPACT_GRID("compact_grid", "三列");

    companion object {
        fun fromPersisted(value: String?): GalleryViewMode =
            entries.firstOrNull { it.persistedValue == value } ?: COMFORTABLE_GRID
    }
}
