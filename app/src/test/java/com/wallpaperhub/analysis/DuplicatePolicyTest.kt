package com.wallpaperhub.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DuplicatePolicyTest {
    @Test
    fun onlyCurrentCopyIsHardLockedFromBulkSelection() {
        val current = DuplicateMember(wallpaperId = "current", sha256 = "same", isCurrent = true)
        val cropped = DuplicateMember(wallpaperId = "cropped", sha256 = "same", cropRevision = 1L)
        val playlist = DuplicateMember(wallpaperId = "playlist", sha256 = "same", inPlaylist = true)
        val favorite = DuplicateMember(wallpaperId = "favorite", sha256 = "same", isFavorite = true)
        val plain = DuplicateMember(wallpaperId = "plain", sha256 = "same")

        val decision = DuplicatePolicy.recommendForGroup(
            listOf(plain, favorite, playlist, cropped, current),
            DuplicateKind.EXACT,
        )!!

        assertEquals("current", decision.keeperId)
        assertEquals(setOf("plain", "favorite", "playlist", "cropped"), decision.suggestedDeleteIds)
    }

    @Test
    fun preservationPriorityStillPrefersCropThenPlaylistThenFavorite() {
        val favorite = DuplicateMember(wallpaperId = "favorite", sha256 = "same", isFavorite = true)
        val playlist = DuplicateMember(wallpaperId = "playlist", sha256 = "same", inPlaylist = true)
        val cropped = DuplicateMember(wallpaperId = "cropped", sha256 = "same", cropRevision = 1L)

        val cropDecision = DuplicatePolicy.recommendForGroup(listOf(favorite, playlist, cropped), DuplicateKind.EXACT)!!
        assertEquals("cropped", cropDecision.keeperId)
        assertEquals(setOf("favorite", "playlist"), cropDecision.suggestedDeleteIds)

        val playlistDecision = DuplicatePolicy.recommendForGroup(listOf(favorite, playlist), DuplicateKind.EXACT)!!
        assertEquals("playlist", playlistDecision.keeperId)
        assertEquals(setOf("favorite"), playlistDecision.suggestedDeleteIds)
    }

    @Test
    fun highVisualGroupCanBulkSelectButPossibleGroupCannot() {
        val members = listOf(
            DuplicateMember(wallpaperId = "a", qualityScore = 90, inPlaylist = true),
            DuplicateMember(wallpaperId = "b", qualityScore = 80, isFavorite = true),
            DuplicateMember(wallpaperId = "c", qualityScore = 70, cropRevision = 1L),
        )

        val high = DuplicatePolicy.recommendForGroup(members, DuplicateKind.VISUAL_HIGH)!!
        assertEquals(2, high.suggestedDeleteIds.size)
        assertFalse(high.keeperId in high.suggestedDeleteIds)
        assertNull(DuplicatePolicy.recommendForGroup(members, DuplicateKind.VISUAL_POSSIBLE))
    }

    @Test
    fun ordinaryExactCopiesStillProduceBulkSuggestions() {
        val members = (1..4).map { index ->
            DuplicateMember(wallpaperId = "copy-$index", sha256 = "same", addedAt = index.toLong())
        }
        val decision = DuplicatePolicy.recommendForGroup(members, DuplicateKind.EXACT)!!

        assertEquals(3, decision.suggestedDeleteIds.size)
        assertFalse(decision.keeperId in decision.suggestedDeleteIds)
    }
}
