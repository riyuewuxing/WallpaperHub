package com.wallpaperhub.ui.library

import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType

enum class LibraryMediaFilter {
    ALL,
    IMAGE,
    VIDEO,
}

enum class LibraryOrientation {
    ALL,
    PORTRAIT,
    LANDSCAPE,
    SQUARE,
}

data class LibraryFilterState(
    val query: String = "",
    val media: LibraryMediaFilter = LibraryMediaFilter.ALL,
    val favoriteOnly: Boolean = false,
    val playlistOnly: Boolean = false,
    val notInPlaylistOnly: Boolean = false,
    val croppedOnly: Boolean = false,
    val orientation: LibraryOrientation = LibraryOrientation.ALL,
    val groupTag: String? = null,
    val ungroupedOnly: Boolean = false,
)

fun WallpaperEntity.matches(filters: LibraryFilterState): Boolean {
    val query = filters.query.trim()
    val matchesQuery = query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        groupTag?.contains(query, ignoreCase = true) == true
    val matchesMedia = when (filters.media) {
        LibraryMediaFilter.ALL -> true
        LibraryMediaFilter.IMAGE -> type == WallpaperType.STATIC
        LibraryMediaFilter.VIDEO -> type == WallpaperType.VIDEO
    }
    val matchesOrientation = when (filters.orientation) {
        LibraryOrientation.ALL -> true
        LibraryOrientation.PORTRAIT -> width != null && height != null && height > width
        LibraryOrientation.LANDSCAPE -> width != null && height != null && width > height
        LibraryOrientation.SQUARE -> width != null && height != null && width == height
    }
    val normalizedGroup = groupTag?.trim().orEmpty()
    val matchesGroup = when {
        filters.ungroupedOnly -> normalizedGroup.isBlank()
        !filters.groupTag.isNullOrBlank() -> normalizedGroup.equals(filters.groupTag.trim(), ignoreCase = true)
        else -> true
    }
    return matchesQuery &&
        matchesMedia &&
        (!filters.favoriteOnly || isFavorite) &&
        (!filters.playlistOnly || inPlaylist) &&
        (!filters.notInPlaylistOnly || !inPlaylist) &&
        (!filters.croppedOnly || cropRevision > 0L) &&
        matchesOrientation &&
        matchesGroup
}

fun LibraryFilterState.activeCount(): Int = buildList {
    if (query.isNotBlank()) add(Unit)
    if (media != LibraryMediaFilter.ALL) add(Unit)
    if (favoriteOnly) add(Unit)
    if (playlistOnly) add(Unit)
    if (notInPlaylistOnly) add(Unit)
    if (croppedOnly) add(Unit)
    if (orientation != LibraryOrientation.ALL) add(Unit)
    if (!groupTag.isNullOrBlank() || ungroupedOnly) add(Unit)
}.size
