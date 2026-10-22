package com.wallpaperhub.playback.engine

import android.content.Context
import androidx.room.withTransaction
import com.wallpaperhub.data.db.AppDatabase
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.repo.PlaybackRepository
import com.wallpaperhub.data.repo.PlaybackRuntimePolicy
import com.wallpaperhub.playback.model.PlaybackMode as DomainPlaybackMode
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.wallpaper.VideoWallpaperSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The only writer of playlist membership/order.
 *
 * Every explicit regeneration anchors the effective runtime wallpaper at slot 0. Time direction,
 * shuffle and random are plan-generation concerns; WallpaperService only consumes the concrete plan.
 */
object PlaybackOrderCoordinator {
    private val mutationMutex = Mutex()

    data class Result(
        val mode: VideoWallpaperSettings.PlaybackMode,
        val currentUri: String?,
        val queueSize: Int,
        val firstUri: String?,
        val revision: Long,
        val queue: List<String> = emptyList(),
        val currentChanged: Boolean = false,
    )

    suspend fun switchMode(
        context: Context,
        mode: VideoWallpaperSettings.PlaybackMode,
        sequentialReversed: Boolean? = null,
    ): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val oldSettings = VideoWallpaperSettings.read(env.appContext)
            val reverse = if (mode == VideoWallpaperSettings.PlaybackMode.SEQUENTIAL) {
                sequentialReversed ?: oldSettings.sequentialReversed
            } else oldSettings.sequentialReversed
            val canonical = canonicalNewestFirst(env)
            if (canonical.isEmpty()) {
                VideoWallpaperSettings.updatePlaybackMode(env.appContext, mode)
                if (mode == VideoWallpaperSettings.PlaybackMode.SEQUENTIAL) {
                    VideoWallpaperSettings.updateSequentialReversed(env.appContext, reverse)
                }
                return@withLock Result(mode, null, 0, null, env.playbackRepository.playlistRevision())
            }

            val uris = canonical.map { it.uri }
            val current = resolveCurrent(env, uris)
            val plan = materialize(uris, current, mode, reverse)
            val revision = persistAndPublish(env, canonical, plan, current)
            VideoWallpaperSettings.updatePlaybackMode(env.appContext, mode)
            if (mode == VideoWallpaperSettings.PlaybackMode.SEQUENTIAL) {
                VideoWallpaperSettings.updateSequentialReversed(env.appContext, reverse)
            }
            logPlan("mode_plan_regenerated", mode, reverse, current, plan, revision)
            Result(mode, current, plan.size, plan.firstOrNull(), revision, plan)
        }
    }

    /** Import/manual add: add membership once, then regenerate the whole future from runtime current. */
    suspend fun addMembers(
        context: Context,
        entities: List<WallpaperEntity>,
    ): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val settings = VideoWallpaperSettings.read(env.appContext)
            if (entities.isEmpty()) return@withLock snapshotResult(env, settings.playbackMode)

            val existing = canonicalNewestFirst(env)
            val existingUris = existing.mapTo(HashSet()) { it.uri }
            val fresh = env.dao.findByUris(entities.map { it.uri }.distinct())
                .filter { it.uri !in existingUris }
                .distinctBy { it.uri }
            if (fresh.isEmpty()) return@withLock snapshotResult(env, settings.playbackMode)

            val canonical = sortNewestFirst(existing + fresh)
            val uris = canonical.map { it.uri }
            val current = resolveCurrent(env, uris)
            val plan = materialize(uris, current, settings.playbackMode, settings.sequentialReversed)
            val revision = persistAndPublish(env, canonical, plan, current)
            WallpaperDebugLog.i(
                "playback_order",
                "members_added_plan_regenerated",
                "mode" to settings.playbackMode,
                "reverse" to settings.sequentialReversed,
                "added" to fresh.size,
                "queueSize" to plan.size,
                "current" to WallpaperDebugLog.uri(current),
                "revision" to revision,
            )
            Result(settings.playbackMode, current, plan.size, plan.firstOrNull(), revision, plan)
        }
    }

    suspend fun removeMembers(
        context: Context,
        entities: List<WallpaperEntity>,
    ): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val settings = VideoWallpaperSettings.read(env.appContext)
            if (entities.isEmpty()) return@withLock snapshotResult(env, settings.playbackMode)

            val oldCanonical = canonicalNewestFirst(env)
            val oldUris = oldCanonical.map { it.uri }
            val removeUris = entities.map { it.uri }.toSet().intersect(oldUris.toSet())
            if (removeUris.isEmpty()) return@withLock snapshotResult(env, settings.playbackMode)

            val previousCurrent = resolveCurrent(env, oldUris)
            val canonical = sortNewestFirst(oldCanonical.filterNot { it.uri in removeUris })
            val uris = canonical.map { it.uri }
            val currentRemoved = previousCurrent != null && previousCurrent in removeUris
            val nextCurrent = when {
                uris.isEmpty() -> null
                previousCurrent != null && previousCurrent in uris -> previousCurrent
                settings.playbackMode == VideoWallpaperSettings.PlaybackMode.SEQUENTIAL &&
                    settings.sequentialReversed -> uris.last()
                settings.playbackMode == VideoWallpaperSettings.PlaybackMode.SHUFFLE ||
                    settings.playbackMode == VideoWallpaperSettings.PlaybackMode.RANDOM ->
                    PlaybackPlanPolicy.randomSuccessor(uris) ?: uris.first()
                else -> uris.first()
            }
            val plan = if (uris.isEmpty()) emptyList() else {
                materialize(uris, nextCurrent, settings.playbackMode, settings.sequentialReversed)
            }

            val removedRows = env.dao.findByUris(removeUris.toList())
            env.db.withTransaction {
                if (canonical.isNotEmpty()) env.dao.updateAll(activeEntities(canonical, plan))
                if (removedRows.isNotEmpty()) {
                    env.dao.updateAll(
                        removedRows.map {
                            it.copy(inPlaylist = false, playlistOrder = 0L, playlistBaseOrder = 0L)
                        }
                    )
                }
            }
            val revision = env.playbackRepository.syncPlaylist(plan, nextCurrent)
            publishCursorIfVisible(env, plan, nextCurrent)
            WallpaperDebugLog.i(
                "playback_order",
                "members_removed_plan_regenerated",
                "mode" to settings.playbackMode,
                "reverse" to settings.sequentialReversed,
                "removed" to removeUris.size,
                "queueSize" to plan.size,
                "previousCurrent" to WallpaperDebugLog.uri(previousCurrent),
                "current" to WallpaperDebugLog.uri(nextCurrent),
                "currentChanged" to currentRemoved,
                "revision" to revision,
            )
            Result(
                settings.playbackMode,
                nextCurrent,
                plan.size,
                plan.firstOrNull(),
                revision,
                plan,
                currentChanged = currentRemoved,
            )
        }
    }

    suspend fun clearMembers(context: Context): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val mode = VideoWallpaperSettings.read(env.appContext).playbackMode
            val members = canonicalNewestFirst(env)
            if (members.isNotEmpty()) {
                env.db.withTransaction {
                    env.dao.updateAll(
                        members.map {
                            it.copy(inPlaylist = false, playlistOrder = 0L, playlistBaseOrder = 0L)
                        }
                    )
                }
            }
            val revision = env.playbackRepository.syncPlaylist(emptyList(), null)
            WallpaperDebugLog.i("playback_order", "members_cleared", "removed" to members.size, "revision" to revision)
            Result(mode, null, 0, null, revision, emptyList())
        }
    }

    /** Import-time order is authoritative; legacy manual reorder requests regenerate that order. */
    suspend fun reorderCanonical(
        context: Context,
        @Suppress("UNUSED_PARAMETER") ordered: List<WallpaperEntity>,
    ): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val settings = VideoWallpaperSettings.read(env.appContext)
            val canonical = canonicalNewestFirst(env)
            if (canonical.isEmpty()) return@withLock snapshotResult(env, settings.playbackMode)
            val uris = canonical.map { it.uri }
            val current = resolveCurrent(env, uris)
            val plan = materialize(uris, current, settings.playbackMode, settings.sequentialReversed)
            val revision = persistAndPublish(env, canonical, plan, current)
            Result(settings.playbackMode, current, plan.size, plan.firstOrNull(), revision, plan)
        }
    }

    /** Explicit user apply/restart: selected becomes slot 0 and a complete plan is regenerated. */
    suspend fun restartFrom(
        context: Context,
        selected: WallpaperEntity,
    ): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val settings = VideoWallpaperSettings.read(env.appContext)
            val freshSelected = env.dao.findById(selected.id) ?: selected
            val existing = canonicalNewestFirst(env)
            val canonical = sortNewestFirst(
                if (existing.any { it.uri == freshSelected.uri }) existing else existing + freshSelected
            )
            val uris = canonical.map { it.uri }
            val current = freshSelected.uri
            val plan = materialize(uris, current, settings.playbackMode, settings.sequentialReversed)
            val revision = persistAndPublish(env, canonical, plan, current)
            WallpaperDebugLog.i(
                "playback_order",
                "restart_from_materialized",
                "mode" to settings.playbackMode,
                "reverse" to settings.sequentialReversed,
                "current" to WallpaperDebugLog.uri(current),
                "queueSize" to plan.size,
                "firstIsCurrent" to (plan.firstOrNull() == current),
                "revision" to revision,
            )
            Result(settings.playbackMode, current, plan.size, plan.firstOrNull(), revision, plan, currentChanged = true)
        }
    }

    suspend fun completeShuffleRound(
        context: Context,
        currentUri: String,
    ): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val settings = VideoWallpaperSettings.read(env.appContext)
            if (settings.playbackMode != VideoWallpaperSettings.PlaybackMode.SHUFFLE) {
                return@withLock snapshotResult(env, settings.playbackMode)
            }
            regenerateRandomizedRoundLocked(env, settings, currentUri)
        }
    }

    /**
     * Renderer-confirmed progress normally updates metadata only. A full rewrite is allowed only at
     * the end of SHUFFLE/RANDOM, where a new complete round is genuinely required.
     */
    suspend fun prepareForVisibleCurrent(
        context: Context,
        currentUri: String,
    ): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val settings = VideoWallpaperSettings.read(env.appContext)
            val latestSession = env.playbackRepository.getSession()
            val effectiveCurrent = PlaybackRuntimePolicy.effectiveCursorUri(
                appliedUri = latestSession.appliedUri,
                desiredUri = latestSession.desiredUri,
                phase = latestSession.phase,
            )
            val staleCurrent = effectiveCurrent != currentUri
            val supersededByDesired = latestSession.desiredUri != null &&
                latestSession.desiredUri != currentUri
            if (staleCurrent || supersededByDesired) {
                WallpaperDebugLog.w(
                    "playback_order",
                    "stale_visible_current_ignored",
                    "observed" to WallpaperDebugLog.uri(currentUri),
                    "effective" to WallpaperDebugLog.uri(effectiveCurrent),
                    "latestApplied" to WallpaperDebugLog.uri(latestSession.appliedUri),
                    "desired" to WallpaperDebugLog.uri(latestSession.desiredUri),
                    "staleCurrent" to staleCurrent,
                    "supersededByDesired" to supersededByDesired,
                    "mode" to settings.playbackMode,
                )
                return@withLock snapshotResult(env, settings.playbackMode)
            }

            val canonical = canonicalNewestFirst(env)
            if (canonical.isEmpty()) return@withLock snapshotResult(env, settings.playbackMode)
            val canonicalUris = canonical.map { it.uri }
            if (currentUri !in canonicalUris) return@withLock snapshotResult(env, settings.playbackMode)

            var active = env.dao.playlistSnapshot().map { it.uri }
            val membershipMatches = active.size == canonicalUris.size && active.toSet() == canonicalUris.toSet()
            if (!membershipMatches || currentUri !in active) {
                active = materialize(
                    canonicalUris,
                    currentUri,
                    settings.playbackMode,
                    settings.sequentialReversed,
                )
                val revision = persistAndPublish(env, canonical, active, currentUri)
                logPlan(
                    "runtime_plan_repaired",
                    settings.playbackMode,
                    settings.sequentialReversed,
                    currentUri,
                    active,
                    revision,
                )
                return@withLock Result(
                    settings.playbackMode,
                    currentUri,
                    active.size,
                    active.firstOrNull(),
                    revision,
                    active,
                )
            }

            val index = active.indexOf(currentUri)
            val randomizedRoundEnded = active.size > 1 && index == active.lastIndex &&
                (settings.playbackMode == VideoWallpaperSettings.PlaybackMode.SHUFFLE ||
                    settings.playbackMode == VideoWallpaperSettings.PlaybackMode.RANDOM)
            if (randomizedRoundEnded) {
                return@withLock regenerateRandomizedRoundLocked(env, settings, currentUri)
            }

            val next = successor(active, index)
            env.playbackRepository.updatePlaybackPosition(currentUri, index.coerceAtLeast(0), next)
            Result(
                settings.playbackMode,
                currentUri,
                active.size,
                active.firstOrNull(),
                env.playbackRepository.playlistRevision(),
                active,
            )
        }
    }

    /**
     * Repairs only the stable automatic-runtime mismatch after the renderer safety window has elapsed.
     * Unlike prepareForVisibleCurrent, this method always finishes by publishing applied/currentIndex,
     * including when recovery itself repairs membership or crosses the end of a randomized round.
     */
    suspend fun recoverUncommittedRuntimeCurrent(
        context: Context,
        currentUri: String,
    ): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val settings = VideoWallpaperSettings.read(env.appContext)
            val session = env.playbackRepository.getSession()
            val recoverable = PlaybackRuntimePolicy.canRecoverUncommittedRuntimeCursor(
                appliedUri = session.appliedUri,
                desiredUri = session.desiredUri,
                phase = session.phase,
            ) && session.desiredUri == currentUri
            if (!recoverable) {
                WallpaperDebugLog.d(
                    "playback_order",
                    "runtime_cursor_recovery_skipped",
                    "candidate" to WallpaperDebugLog.uri(currentUri),
                    "applied" to WallpaperDebugLog.uri(session.appliedUri),
                    "desired" to WallpaperDebugLog.uri(session.desiredUri),
                    "phase" to session.phase,
                )
                return@withLock snapshotResult(env, settings.playbackMode)
            }

            val canonical = canonicalNewestFirst(env)
            if (canonical.isEmpty()) return@withLock snapshotResult(env, settings.playbackMode)
            val canonicalUris = canonical.map { it.uri }
            if (currentUri !in canonicalUris) return@withLock snapshotResult(env, settings.playbackMode)

            var active = env.dao.playlistSnapshot().map { it.uri }
            var revision = env.playbackRepository.playlistRevision()
            val membershipMatches = active.size == canonicalUris.size && active.toSet() == canonicalUris.toSet()
            if (!membershipMatches || currentUri !in active) {
                active = materialize(
                    canonicalUris,
                    currentUri,
                    settings.playbackMode,
                    settings.sequentialReversed,
                )
                revision = persistAndPublish(env, canonical, active, currentUri)
            } else {
                val index = active.indexOf(currentUri)
                val randomizedRoundEnded = active.size > 1 && index == active.lastIndex &&
                    (settings.playbackMode == VideoWallpaperSettings.PlaybackMode.SHUFFLE ||
                        settings.playbackMode == VideoWallpaperSettings.PlaybackMode.RANDOM)
                if (randomizedRoundEnded) {
                    val regenerated = regenerateRandomizedRoundLocked(env, settings, currentUri)
                    active = regenerated.queue
                    revision = regenerated.revision
                }
            }

            val finalIndex = active.indexOf(currentUri).coerceAtLeast(0)
            val next = successor(active, finalIndex)
            env.playbackRepository.updatePlaybackPosition(currentUri, finalIndex, next)
            WallpaperDebugLog.w(
                "playback_order",
                "runtime_cursor_recovered",
                "current" to WallpaperDebugLog.uri(currentUri),
                "index" to finalIndex,
                "next" to WallpaperDebugLog.uri(next),
                "queueSize" to active.size,
                "revision" to revision,
                "mode" to settings.playbackMode,
            )
            Result(
                settings.playbackMode,
                currentUri,
                active.size,
                active.firstOrNull(),
                revision,
                active,
            )
        }
    }

    suspend fun reconcile(context: Context): Result = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val env = Env(context)
            val settings = VideoWallpaperSettings.read(env.appContext)
            val canonical = canonicalNewestFirst(env)
            if (canonical.isEmpty()) {
                val revision = env.playbackRepository.syncPlaylist(emptyList(), null)
                return@withLock Result(settings.playbackMode, null, 0, null, revision)
            }
            val uris = canonical.map { it.uri }
            val current = resolveCurrent(env, uris)
            val active = env.dao.playlistSnapshot().map { it.uri }
            val preserveExisting = PlaybackStartupPlanPolicy.shouldPreserve(
                canonical = uris,
                active = active,
                currentUri = current,
            )
            val plan: List<String>
            val revision: Long
            if (preserveExisting) {
                // Process recreation is not a user-requested regeneration. Keep the persisted
                // concrete round so SHUFFLE/RANDOM future order and sequential progress survive
                // process death without rewriting thousands of playlistOrder rows.
                plan = active
                publishCursorIfVisible(env, plan, current)
                revision = env.playbackRepository.playlistRevision()
            } else {
                plan = materialize(uris, current, settings.playbackMode, settings.sequentialReversed)
                revision = persistAndPublish(env, canonical, plan, current)
            }
            logPlan(
                if (preserveExisting) "storage_reconciled_plan_preserved" else "storage_reconciled_plan_regenerated",
                settings.playbackMode,
                settings.sequentialReversed,
                current,
                plan,
                revision,
            )
            Result(settings.playbackMode, current, plan.size, plan.firstOrNull(), revision, plan)
        }
    }

    private suspend fun regenerateRandomizedRoundLocked(
        env: Env,
        settings: VideoWallpaperSettings.Settings,
        currentUri: String,
    ): Result {
        val canonical = canonicalNewestFirst(env)
        val uris = canonical.map { it.uri }
        if (currentUri !in uris) return snapshotResult(env, settings.playbackMode)
        val plan = materialize(uris, currentUri, settings.playbackMode, settings.sequentialReversed)
        val revision = persistAndPublish(env, canonical, plan, currentUri)
        logPlan(
            "randomized_round_regenerated",
            settings.playbackMode,
            settings.sequentialReversed,
            currentUri,
            plan,
            revision,
        )
        return Result(settings.playbackMode, currentUri, plan.size, plan.firstOrNull(), revision, plan)
    }

    private fun materialize(
        newestFirst: List<String>,
        current: String?,
        mode: VideoWallpaperSettings.PlaybackMode,
        sequentialReversed: Boolean,
    ): List<String> = PlaybackPlanPolicy.materialize(
        canonical = newestFirst,
        currentUri = current,
        mode = mode.toDomainMode(),
        reverseSequential = mode == VideoWallpaperSettings.PlaybackMode.SEQUENTIAL && sequentialReversed,
    )

    private suspend fun persistAndPublish(
        env: Env,
        canonical: List<WallpaperEntity>,
        plan: List<String>,
        current: String?,
    ): Long {
        if (canonical.isNotEmpty()) {
            val desiredRows = activeEntities(canonical, plan)
            val oldRows = env.dao.playlistSnapshot()
            val oldByUri = oldRows.associateBy { it.uri }
            val changedRows = desiredRows.filter { desired ->
                val old = oldByUri[desired.uri]
                old == null || !old.inPlaylist || old.playlistOrder != desired.playlistOrder
            }
            if (changedRows.isNotEmpty()) {
                env.db.withTransaction { env.dao.updateAll(changedRows) }
            }
        }
        val revision = env.playbackRepository.syncPlaylist(plan, current)
        publishCursorIfVisible(env, plan, current)
        return revision
    }

    private suspend fun publishCursorIfVisible(
        env: Env,
        plan: List<String>,
        current: String?,
    ) {
        if (current == null || plan.isEmpty()) return
        val session = env.playbackRepository.getSession()
        if (session.appliedUri != current ||
            (session.desiredUri != null && session.desiredUri != current)
        ) return
        val index = plan.indexOf(current).coerceAtLeast(0)
        val next = successor(plan, index)
        env.playbackRepository.updatePlaybackPosition(current, index, next)
    }

    private suspend fun snapshotResult(
        env: Env,
        mode: VideoWallpaperSettings.PlaybackMode,
    ): Result {
        val active = env.dao.playlistSnapshot().map { it.uri }
        val current = resolveCurrent(env, active)
        return Result(mode, current, active.size, active.firstOrNull(), env.playbackRepository.playlistRevision(), active)
    }

    private suspend fun resolveCurrent(env: Env, validUris: List<String>): String? {
        if (validUris.isEmpty()) return null
        val valid = validUris.toHashSet()
        val session = env.playbackRepository.getSession()
        PlaybackRuntimePolicy.effectiveCursorUri(
            appliedUri = session.appliedUri,
            desiredUri = session.desiredUri,
            phase = session.phase,
        )?.takeIf { it in valid }?.let { return it }
        env.playbackRepository.getPlaylistState()?.currentUri?.takeIf { it in valid }?.let { return it }
        return validUris.first()
    }

    private fun successor(queue: List<String>, index: Int): String? = when {
        queue.isEmpty() -> null
        queue.size == 1 -> queue.first()
        index in queue.indices -> queue[(index + 1) % queue.size]
        else -> queue.firstOrNull()
    }

    private suspend fun canonicalNewestFirst(env: Env): List<WallpaperEntity> =
        sortNewestFirst(env.dao.canonicalPlaylistSnapshot())

    private fun sortNewestFirst(items: List<WallpaperEntity>): List<WallpaperEntity> =
        items.distinctBy { it.uri }
            .sortedWith(compareByDescending<WallpaperEntity> { it.addedAt }.thenByDescending { it.id })

    private fun activeEntities(
        canonical: List<WallpaperEntity>,
        activeUris: List<String>,
    ): List<WallpaperEntity> {
        val byUri = sortNewestFirst(canonical).associateBy { it.uri }
        return activeUris.distinct().mapIndexedNotNull { index, uri ->
            // playlistBaseOrder is legacy compatibility only. Import time is the canonical source,
            // so do not rewrite thousands of rows merely because one new item shifted a rank.
            byUri[uri]?.copy(
                inPlaylist = true,
                playlistOrder = index + 1L,
            )
        }
    }

    private fun VideoWallpaperSettings.PlaybackMode.toDomainMode(): DomainPlaybackMode = when (this) {
        VideoWallpaperSettings.PlaybackMode.SEQUENTIAL -> DomainPlaybackMode.SEQUENTIAL
        VideoWallpaperSettings.PlaybackMode.SHUFFLE -> DomainPlaybackMode.SHUFFLE
        VideoWallpaperSettings.PlaybackMode.RANDOM -> DomainPlaybackMode.RANDOM
    }

    private fun logPlan(
        event: String,
        mode: VideoWallpaperSettings.PlaybackMode,
        reverse: Boolean,
        current: String?,
        queue: List<String>,
        revision: Long,
    ) {
        WallpaperDebugLog.i(
            "playback_order",
            event,
            "mode" to mode,
            "reverse" to reverse,
            "current" to WallpaperDebugLog.uri(current),
            "first" to WallpaperDebugLog.uri(queue.firstOrNull()),
            "next" to WallpaperDebugLog.uri(queue.getOrNull(1)),
            "queueSize" to queue.size,
            "revision" to revision,
        )
    }

    private class Env(context: Context) {
        val appContext: Context = context.applicationContext
        val db = AppDatabase.getInstance(appContext)
        val dao = db.wallpaperDao()
        val playbackRepository = PlaybackRepository.getInstance(appContext)
    }
}
