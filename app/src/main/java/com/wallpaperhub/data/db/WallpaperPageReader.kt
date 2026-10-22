package com.wallpaperhub.data.db

/**
 * Reassembles large logical result sets from bounded SQLite reads.
 *
 * WallpaperHub libraries can contain many thousands of rows. Reading the complete 18-column
 * WallpaperEntity set through one Android CursorWindow has crashed on-device at a window boundary.
 * Keeping every physical query small avoids coupling library size to CursorWindow capacity while
 * preserving the same logical ordering supplied by the DAO page query.
 */
internal object WallpaperPageReader {
    const val DEFAULT_PAGE_SIZE = 256

    suspend fun <T> readAll(
        pageSize: Int = DEFAULT_PAGE_SIZE,
        loadPage: suspend (limit: Int, offset: Int) -> List<T>,
    ): List<T> {
        require(pageSize > 0) { "pageSize must be positive" }
        val result = ArrayList<T>()
        var offset = 0

        while (true) {
            val page = loadPage(pageSize, offset)
            check(page.size <= pageSize) { "Page loader returned more rows than requested" }
            result.addAll(page)
            if (page.size < pageSize) return result
            offset += page.size
        }
    }
}
