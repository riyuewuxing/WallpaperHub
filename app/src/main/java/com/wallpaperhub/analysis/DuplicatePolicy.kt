package com.wallpaperhub.analysis

import kotlin.math.abs

enum class DuplicateKind { EXACT, VISUAL_HIGH, VISUAL_POSSIBLE, NONE }

data class DuplicateMember(
    val wallpaperId: String,
    val sha256: String? = null,
    val visualHashes: List<Long> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val qualityScore: Int? = null,
    val fileSizeBytes: Long = 0L,
    val isCurrent: Boolean = false,
    val inPlaylist: Boolean = false,
    val isFavorite: Boolean = false,
    val cropRevision: Long = 0L,
    val addedAt: Long = 0L,
) {
    /**
     * Only the actually live wallpaper is a hard lock. Playlist/favorite/crop state still strongly
     * influences which member is recommended as the keeper, but must not make bulk selection look
     * broken in a library where nearly every imported item participates in the carousel.
     */
    val isHardProtected: Boolean
        get() = isCurrent

    val pixelCount: Long
        get() = (width?.toLong() ?: 0L) * (height?.toLong() ?: 0L)
}

data class DuplicateGroupDecision(
    val keeperId: String,
    val suggestedDeleteIds: Set<String>,
)

object DuplicatePolicy {
    private const val HIGH_VISUAL_SIMILARITY = 0.94
    private const val POSSIBLE_VISUAL_SIMILARITY = 0.86

    fun classify(a: DuplicateMember, b: DuplicateMember): DuplicateKind {
        if (!a.sha256.isNullOrBlank() && a.sha256 == b.sha256) return DuplicateKind.EXACT
        if (!compatibleForVisualComparison(a, b)) return DuplicateKind.NONE
        val similarity = MediaDigest.sequenceSimilarity(a.visualHashes, b.visualHashes) ?: return DuplicateKind.NONE
        return when {
            similarity >= HIGH_VISUAL_SIMILARITY -> DuplicateKind.VISUAL_HIGH
            similarity >= POSSIBLE_VISUAL_SIMILARITY -> DuplicateKind.VISUAL_POSSIBLE
            else -> DuplicateKind.NONE
        }
    }

    fun recommendForGroup(members: List<DuplicateMember>, kind: DuplicateKind): DuplicateGroupDecision? {
        if (members.size < 2 || kind == DuplicateKind.NONE || kind == DuplicateKind.VISUAL_POSSIBLE) return null
        val keeper = members.maxByOrNull(::preservationRank) ?: return null
        val suggested = members.asSequence()
            .filter { it.wallpaperId != keeper.wallpaperId }
            .filterNot { it.isHardProtected }
            // EXACT is byte-identical. VISUAL_HIGH is explicitly the high-confidence review bucket;
            // the user still confirms removal, while VISUAL_POSSIBLE never enters bulk selection.
            .map { it.wallpaperId }
            .toSet()
        return DuplicateGroupDecision(keeper.wallpaperId, suggested)
    }

    private fun compatibleForVisualComparison(a: DuplicateMember, b: DuplicateMember): Boolean {
        val aDuration = a.durationMs
        val bDuration = b.durationMs
        if (aDuration != null && bDuration != null) {
            val maxDuration = maxOf(aDuration, bDuration).coerceAtLeast(1L)
            if (abs(aDuration - bDuration).toDouble() / maxDuration > 0.08) return false
        }
        val aWidth = a.width
        val aHeight = a.height
        val bWidth = b.width
        val bHeight = b.height
        if (aWidth != null && aHeight != null && bWidth != null && bHeight != null && aHeight > 0 && bHeight > 0) {
            val aRatio = aWidth.toDouble() / aHeight
            val bRatio = bWidth.toDouble() / bHeight
            if (abs(aRatio - bRatio) > 0.08) return false
        }
        return true
    }

    private fun preservationRank(member: DuplicateMember): Long {
        var score = 0L
        if (member.isCurrent) score += 1_000_000_000_000L
        if (member.cropRevision > 0L) score += 100_000_000_000L
        if (member.inPlaylist) score += 50_000_000_000L
        if (member.isFavorite) score += 20_000_000_000L
        score += (member.qualityScore ?: 0).toLong() * 100_000_000L
        score += member.pixelCount.coerceAtMost(50_000_000L) * 100L
        score += (member.fileSizeBytes / 1024L).coerceAtMost(10_000_000L)
        score += (Long.MAX_VALUE - member.addedAt.coerceAtLeast(0L)) / 1_000_000_000L
        return score
    }
}
