package com.wallpaperhub.playback.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogSanitizerTest {

    @Test
    fun redactsUrisAndDevicePathsFromFreeFormFields() {
        val sanitized = DebugLogSanitizer.field(
            "failed content://media/external/video/media/123 " +
                "file:///storage/emulated/0/DCIM/private.mp4 " +
                "C:\\Users\\tester\\Videos\\private.mp4 " +
                "/storage/emulated/0/Download/private.mp4"
        )

        assertFalse(sanitized.contains("content://"))
        assertFalse(sanitized.contains("file://"))
        assertFalse(sanitized.contains("C:\\Users"))
        assertFalse(sanitized.contains("/storage/emulated"))
        assertTrue(sanitized.contains("<redacted_uri>"))
        assertTrue(sanitized.contains("<redacted_path>"))
    }

    @Test
    fun throwableStackKeepsTypesAndFramesButNeverThrowableMessages() {
        val error = IllegalStateException("decoder failed at content://media/private/42")

        val stack = DebugLogSanitizer.throwableStack(error).joinToString("\n")

        assertTrue(stack.contains("IllegalStateException"))
        assertFalse(stack.contains("decoder failed"))
        assertFalse(stack.contains("content://"))
    }
}
