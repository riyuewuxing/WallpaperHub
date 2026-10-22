package com.wallpaperhub.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLayoutPolicyTest {
    @Test
    fun phone_widths_always_use_two_gallery_columns() {
        assertEquals(2, LibraryLayoutPolicy.columnsFor(360))
        assertEquals(2, LibraryLayoutPolicy.columnsFor(412))
    }

    @Test
    fun portrait_stage_gets_real_vertical_space() {
        assertEquals(404, LibraryLayoutPolicy.heroHeightDp(412))
        assertEquals(438, LibraryLayoutPolicy.heroHeightDp(800))
    }

    @Test
    fun user_selected_view_mode_controls_gallery_density() {
        assertEquals(1, LibraryLayoutPolicy.columnsFor(412, GalleryViewMode.LIST))
        assertEquals(1, LibraryLayoutPolicy.columnsFor(412, GalleryViewMode.LARGE_GRID))
        assertEquals(2, LibraryLayoutPolicy.columnsFor(412, GalleryViewMode.COMFORTABLE_GRID))
        assertEquals(3, LibraryLayoutPolicy.columnsFor(412, GalleryViewMode.COMPACT_GRID))
        assertEquals(4, LibraryLayoutPolicy.columnsFor(800, GalleryViewMode.COMPACT_GRID))
    }
}
