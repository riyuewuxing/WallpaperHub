package com.wallpaperhub.preview.model

enum class MediaScaleMode(val persistedValue: String) {
    FILL("fill"),
    FIT("fit");

    companion object {
        fun fromPersisted(value: String?): MediaScaleMode = entries.firstOrNull {
            it.persistedValue.equals(value, ignoreCase = true)
        } ?: FILL
    }
}
