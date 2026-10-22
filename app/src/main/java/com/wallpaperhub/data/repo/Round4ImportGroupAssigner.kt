package com.wallpaperhub.data.repo

import android.content.Context
import com.wallpaperhub.data.db.AppDatabase

/** Assigns optional group metadata to only accepted entries created by one import batch. */
class Round4ImportGroupAssigner(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).wallpaperDao()

    suspend fun assignFresh(beforeIds: Set<String>, rawTag: String?): Int {
        val tag = (rawTag ?: Round4ImportGroupPreference.read(appContext))
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(48)
            ?: return 0
        val fresh = dao.allSnapshot().filter { it.id !in beforeIds }
        if (fresh.isEmpty()) return 0
        dao.updateAll(fresh.map { it.copy(groupTag = tag) })
        return fresh.size
    }
}
