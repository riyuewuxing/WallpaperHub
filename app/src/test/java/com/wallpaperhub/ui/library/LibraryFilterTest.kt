package com.wallpaperhub.ui.library

import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    @Test
    fun queryAndMediaFilterKeepOnlyMatchingWallpaper() {
        val filters = LibraryFilterState(query = "海", media = LibraryMediaFilter.VIDEO)

        assertTrue(wallpaper(name = "海面微光", type = WallpaperType.VIDEO).matches(filters))
        assertFalse(wallpaper(name = "海边静图", type = WallpaperType.STATIC).matches(filters))
    }

    @Test
    fun orientationFilterUsesStoredMediaDimensions() {
        val portrait = wallpaper(name = "Portrait", width = 1080, height = 2400)
        val landscape = wallpaper(name = "Landscape", width = 2400, height = 1080)

        assertTrue(portrait.matches(LibraryFilterState(orientation = LibraryOrientation.PORTRAIT)))
        assertFalse(landscape.matches(LibraryFilterState(orientation = LibraryOrientation.PORTRAIT)))
    }

    @Test
    fun croppedFilterRequiresPersistedCropRevision() {
        val filters = LibraryFilterState(croppedOnly = true)

        assertTrue(wallpaper(name = "Adjusted", cropRevision = 2L).matches(filters))
        assertFalse(wallpaper(name = "Default").matches(filters))
    }

    @Test
    fun queryAlsoMatchesGroupTag() {
        val entity = wallpaper(name = "001", groupTag = "赛博城市")

        assertTrue(entity.matches(LibraryFilterState(query = "赛博")))
        assertFalse(entity.matches(LibraryFilterState(query = "森林")))
    }

    @Test
    fun explicitGroupFilterIsCaseInsensitive() {
        val entity = wallpaper(name = "Grouped", groupTag = "Anime")

        assertTrue(entity.matches(LibraryFilterState(groupTag = "anime")))
        assertFalse(entity.matches(LibraryFilterState(groupTag = "Nature")))
    }

    @Test
    fun ungroupedFilterAcceptsNullOrBlankOnly() {
        val filters = LibraryFilterState(ungroupedOnly = true)

        assertTrue(wallpaper(name = "Null", groupTag = null).matches(filters))
        assertTrue(wallpaper(name = "Blank", groupTag = "   ").matches(filters))
        assertFalse(wallpaper(name = "Tagged", groupTag = "人物").matches(filters))
    }

    @Test
    fun groupFilterCombinesWithMediaFavoritePlaylistAndOrientation() {
        val filters = LibraryFilterState(
            media = LibraryMediaFilter.VIDEO,
            favoriteOnly = true,
            playlistOnly = true,
            orientation = LibraryOrientation.PORTRAIT,
            groupTag = "动态人物",
        )
        val matching = wallpaper(
            name = "Match",
            type = WallpaperType.VIDEO,
            width = 1080,
            height = 1920,
            groupTag = "动态人物",
            favorite = true,
            playlist = true,
        )
        val wrongGroup = matching.copy(id = "Wrong", name = "Wrong", uri = "file:///wallpapers/Wrong", groupTag = "风景")

        assertTrue(matching.matches(filters))
        assertFalse(wrongGroup.matches(filters))
    }

    private fun wallpaper(
        name: String,
        type: WallpaperType = WallpaperType.STATIC,
        width: Int? = null,
        height: Int? = null,
        cropRevision: Long = 0L,
        groupTag: String? = null,
        favorite: Boolean = false,
        playlist: Boolean = false,
    ) = WallpaperEntity(
        id = name,
        uri = "file:///wallpapers/$name",
        type = type,
        name = name,
        addedAt = 1L,
        isFavorite = favorite,
        inPlaylist = playlist,
        groupTag = groupTag,
        width = width,
        height = height,
        cropRevision = cropRevision,
    )
}
