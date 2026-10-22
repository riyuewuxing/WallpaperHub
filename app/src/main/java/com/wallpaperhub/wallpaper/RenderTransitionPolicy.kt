package com.wallpaperhub.wallpaper

/** Keeps video playback smooth and lets truly hidden catch-up bypass visible transitions explicitly. */
internal object RenderTransitionPolicy {
    /** Renderer work is serialized on the wallpaper GL thread, so suppression can be scoped locally. */
    private val suppressOffscreenTransition = ThreadLocal<Boolean>()

    fun <T> withoutOffscreenTransition(block: () -> T): T {
        val previous = suppressOffscreenTransition.get() == true
        suppressOffscreenTransition.set(true)
        return try {
            block()
        } finally {
            if (previous) {
                suppressOffscreenTransition.set(true)
            } else {
                suppressOffscreenTransition.remove()
            }
        }
    }

    fun useOffscreenTransition(currentIsVideo: Boolean, nextIsVideo: Boolean): Boolean =
        suppressOffscreenTransition.get() != true &&
            !(currentIsVideo && nextIsVideo)

    /** Keep the already visible video on screen while the replacement decoder warms up. */
    fun shouldPreserveCurrentFrame(currentIsVideo: Boolean, nextIsVideo: Boolean): Boolean =
        currentIsVideo && nextIsVideo
}
