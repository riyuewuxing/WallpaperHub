package com.wallpaperhub.ui.library

/** Responsive gallery decisions kept outside Compose so they can be regression-tested. */
object LibraryLayoutPolicy {
    const val COMPACT_BREAKPOINT_DP = 600
    const val COMPACT_COLUMN_COUNT = 2
    const val EXPANDED_COLUMN_COUNT = 3
    const val COMPACT_HERO_HEIGHT_DP = 404
    const val EXPANDED_HERO_HEIGHT_DP = 438

    fun columnsFor(
        maxWidthDp: Int,
        viewMode: GalleryViewMode = GalleryViewMode.COMFORTABLE_GRID,
    ): Int = when (viewMode) {
        GalleryViewMode.LIST -> 1
        GalleryViewMode.LARGE_GRID -> 1
        GalleryViewMode.COMFORTABLE_GRID ->
            if (maxWidthDp < COMPACT_BREAKPOINT_DP) COMPACT_COLUMN_COUNT else EXPANDED_COLUMN_COUNT
        GalleryViewMode.COMPACT_GRID ->
            if (maxWidthDp < COMPACT_BREAKPOINT_DP) COMPACT_COLUMN_COUNT + 1 else EXPANDED_COLUMN_COUNT + 1
    }

    fun heroHeightDp(maxWidthDp: Int): Int =
        if (maxWidthDp < COMPACT_BREAKPOINT_DP) COMPACT_HERO_HEIGHT_DP else EXPANDED_HERO_HEIGHT_DP
}
