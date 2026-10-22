package com.wallpaperhub.data.repo

import android.content.Context
import com.wallpaperhub.data.db.MediaAnalysisEntity
import com.wallpaperhub.data.db.WallpaperEntity

/**
 * Human review is authoritative over heuristic media hygiene results.
 *
 * Duplicate/similarity decisions are stored pairwise so a reviewed pair stays quiet, while a new
 * third member can still surface for review. Quality decisions are content-sensitive: replacing an
 * item's bytes changes its SHA/identity and makes it eligible for review again.
 */
class MediaReviewDecisionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class PairKind(val prefix: String) {
        EXACT("exact"),
        VISUAL("visual"),
    }

    fun dismissGroup(kind: PairKind, members: List<WallpaperEntity>) {
        if (members.size < 2) return
        val next = pairDecisions().toMutableSet()
        for (left in 0 until members.lastIndex) {
            for (right in left + 1 until members.size) {
                next += pairKey(kind, members[left].id, members[right].id)
            }
        }
        prefs.edit().putStringSet(KEY_PAIR_DECISIONS, next).apply()
    }

    fun dismissPair(kind: PairKind, firstId: String, secondId: String) {
        if (firstId == secondId) return
        val next = pairDecisions().toMutableSet()
        next += pairKey(kind, firstId, secondId)
        prefs.edit().putStringSet(KEY_PAIR_DECISIONS, next).apply()
    }

    fun isGroupDismissed(kind: PairKind, members: List<WallpaperEntity>): Boolean {
        if (members.size < 2) return false
        val decisions = pairDecisions()
        for (left in 0 until members.lastIndex) {
            for (right in left + 1 until members.size) {
                if (pairKey(kind, members[left].id, members[right].id) !in decisions) return false
            }
        }
        return true
    }

    fun dismissQuality(entity: WallpaperEntity, analysis: MediaAnalysisEntity) {
        val next = qualityDecisions().toMutableSet()
        next += qualityKey(entity, analysis)
        prefs.edit().putStringSet(KEY_QUALITY_DECISIONS, next).apply()
    }

    fun isQualityDismissed(entity: WallpaperEntity, analysis: MediaAnalysisEntity): Boolean =
        qualityKey(entity, analysis) in qualityDecisions()

    private fun pairDecisions(): Set<String> = prefs.getStringSet(KEY_PAIR_DECISIONS, emptySet()).orEmpty()
    private fun qualityDecisions(): Set<String> = prefs.getStringSet(KEY_QUALITY_DECISIONS, emptySet()).orEmpty()

    private fun pairKey(kind: PairKind, firstId: String, secondId: String): String {
        val (left, right) = if (firstId <= secondId) firstId to secondId else secondId to firstId
        return "${kind.prefix}|$left|$right"
    }

    private fun qualityKey(entity: WallpaperEntity, analysis: MediaAnalysisEntity): String {
        val identity = analysis.sha256?.takeIf { it.isNotBlank() }
            ?: "${analysis.sourceSizeBytes}:${analysis.sourceLastModified}:${entity.uri}"
        return "quality|${entity.id}|$identity"
    }

    private companion object {
        const val PREFS_NAME = "media_review_decisions_v1"
        const val KEY_PAIR_DECISIONS = "dismissed_pairs"
        const val KEY_QUALITY_DECISIONS = "dismissed_quality"
    }
}
