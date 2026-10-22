package com.wallpaperhub.data.repo

import android.content.Context

/** Small user preference for the optional group assigned to future imports. */
object Round4ImportGroupPreference {
    private const val PREFS = "round4_import_group"
    private const val KEY = "target_group"

    fun read(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(48)

    fun write(context: Context, rawTag: String?) {
        val tag = rawTag?.trim()?.takeIf { it.isNotBlank() }?.take(48)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (tag == null) remove(KEY) else putString(KEY, tag)
            }
            .apply()
    }
}
