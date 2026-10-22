package com.wallpaperhub.ui

import com.wallpaperhub.ui.model.MediaMetadataFormatter
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaMetadataFormatterTest {

    @Test
    fun formatsStorageSizesForPeopleInsteadOfRawBytes() {
        assertEquals("0 B", MediaMetadataFormatter.formatBytes(0))
        assertEquals("1 KB", MediaMetadataFormatter.formatBytes(1024))
        assertEquals("1.5 KB", MediaMetadataFormatter.formatBytes(1536))
        assertEquals("2 MB", MediaMetadataFormatter.formatBytes(2L * 1024 * 1024))
    }

    @Test
    fun formatsVideoDurationAsClockTime() {
        assertEquals("1:05", MediaMetadataFormatter.formatDuration(65_000))
        assertEquals("1:02:03", MediaMetadataFormatter.formatDuration(3_723_000))
    }

    @Test
    fun formatsAddedDateWithoutExposingEpochMilliseconds() {
        assertEquals(
            "2026-08-09 08:30",
            MediaMetadataFormatter.formatAddedDate(
                timestampMillis = 1_786_264_200_000,
                locale = Locale.SIMPLIFIED_CHINESE,
                timeZone = TimeZone.getTimeZone("UTC"),
            ),
        )
    }
}
