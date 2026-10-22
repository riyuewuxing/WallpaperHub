package com.wallpaperhub.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.wallpaperhub.data.db.AppDatabase
import com.wallpaperhub.data.db.PlaybackCommandEntity
import com.wallpaperhub.data.db.PlaybackDao
import com.wallpaperhub.data.db.PlaybackSessionEntity
import com.wallpaperhub.data.db.PlaybackSessionRecord
import com.wallpaperhub.data.db.PlaylistStateEntity
import com.wallpaperhub.data.db.PlaylistStateRecord
import com.wallpaperhub.data.db.WallpaperDao
import com.wallpaperhub.playback.model.PlaybackCommand
import com.wallpaperhub.playback.model.PlaybackCommandType
import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.playback.model.PlaybackSession
import com.wallpaperhub.playback.telemetry.PlaybackTrace
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Durable SSOT facade for user commands and engine-confirmed playback state. */
class PlaybackRepository private constructor(
    private val context: Context,
    private val dao: PlaybackDao,
    private val wallpaperDao: WallpaperDao,
) {
    data class PlaylistStateSnapshot(
        val revision: Long,
        val queue: List<String>,
        val currentUri: String?,
        val currentIndex: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeSession(): Flow<PlaybackSession> = dao.observeSession().map { it.toModel() }

    fun observePlaylistState(): Flow<PlaylistStateSnapshot?> = combine(
        dao.observePlaylistState(),
        wallpaperDao.observePlaylistUris(),
    ) { state, queue -> state?.toSnapshot(queue) }

    fun observeLatestCommand(): Flow<PlaybackCommand?> = dao.observeLatestCommand().map { entity ->
        entity?.let {
            PlaybackCommand(
                id = it.id,
                type = runCatching { PlaybackCommandType.valueOf(it.type) }
                    .getOrDefault(PlaybackCommandType.RETRY),
                targetUri = it.targetUri,
                playlistRevision = it.playlistRevision,
                requestedAt = it.requestedAt,
                source = it.source,
            )
        }
    }

    suspend fun getSession(): PlaybackSession = dao.getSession().toModel()

    suspend fun getPlaylistState(): PlaylistStateSnapshot? {
        val state = dao.getPlaylistState() ?: return null
        return state.toSnapshot(wallpaperDao.playlistUriSnapshot())
    }

    suspend fun latestCommand(): PlaybackCommand? = dao.latestCommand()?.let {
        PlaybackCommand(
            id = it.id,
            type = runCatching { PlaybackCommandType.valueOf(it.type) }
                .getOrDefault(PlaybackCommandType.RETRY),
            targetUri = it.targetUri,
            playlistRevision = it.playlistRevision,
            requestedAt = it.requestedAt,
            source = it.source,
        )
    }

    suspend fun submitApply(uri: String, source: String = "ui"): PlaybackCommand =
        submit(PlaybackCommand(type = PlaybackCommandType.APPLY, targetUri = uri, source = source))

    suspend fun submitNext(source: String = "ui"): PlaybackCommand =
        submit(PlaybackCommand(type = PlaybackCommandType.NEXT, source = source))

    suspend fun submitPrevious(source: String = "ui"): PlaybackCommand =
        submit(PlaybackCommand(type = PlaybackCommandType.PREVIOUS, source = source))

    fun submitApplyAsync(uri: String, source: String = "ui") {
        scope.launch { submitApply(uri, source) }
    }

    /**
     * Active-service apply is preceded by restartPlaylistFromSelected(), so the playlist observer can
     * already be decoding the requested URI before the durable APPLY command is inserted. When the
     * caller proves the requested URI was not already applied before that playlist restart, give the
     * renderer-owned switch a short opportunity to prove its first frame. If it does, record the UI
     * APPLY directly as terminal APPLIED in the same transaction; the service will observe a terminal
     * command and skip redundant decoder creation. Otherwise the normal durable command path is used.
     */
    fun submitApplyAfterRuntimeSwitchAsync(
        uri: String,
        source: String = "ui",
        allowRuntimeCoalesce: Boolean = false,
    ) {
        val command = PlaybackCommand(
            type = PlaybackCommandType.APPLY,
            targetUri = uri,
            source = source,
        )
        scope.launch {
            if (!allowRuntimeCoalesce) {
                submit(command)
                return@launch
            }

            val expectedRevision = playlistRevision()
            val initial = getSession()
            if (ActiveApplyCommandPolicy.canCommitTerminalApply(
                    source = source,
                    targetUri = uri,
                    expectedRevision = expectedRevision,
                    currentRevision = initial.queueRevision,
                    desiredUri = initial.desiredUri,
                    appliedUri = initial.appliedUri,
                    phase = initial.phase,
                )
            ) {
                WallpaperDebugLog.i(
                    "playback_repo",
                    "active_apply_coalesce_runtime_already_confirmed",
                    "command" to WallpaperDebugLog.command(command.id),
                    "target" to WallpaperDebugLog.uri(uri),
                    "revision" to initial.queueRevision,
                    "expectedRevision" to expectedRevision,
                )
                if (commitTerminalApplyIfStillVisible(command, expectedRevision)) {
                    return@launch
                }
            }

            if (ActiveApplyCommandPolicy.shouldAwaitRuntimeConfirmation(
                    source = source,
                    targetUri = uri,
                    desiredUri = initial.desiredUri,
                    appliedUri = initial.appliedUri,
                    phase = initial.phase,
                )
            ) {
                WallpaperDebugLog.i(
                    "playback_repo",
                    "active_apply_coalesce_wait_start",
                    "command" to WallpaperDebugLog.command(command.id),
                    "target" to WallpaperDebugLog.uri(uri),
                    "expectedRevision" to expectedRevision,
                    "previousApplied" to WallpaperDebugLog.uri(initial.appliedUri),
                    "timeoutMs" to ACTIVE_APPLY_COALESCE_TIMEOUT_MS,
                )
                val runtimeConfirmed = withTimeoutOrNull(ACTIVE_APPLY_COALESCE_TIMEOUT_MS) {
                    observeSession().first { session ->
                        ActiveApplyCommandPolicy.canCommitTerminalApply(
                            source = source,
                            targetUri = uri,
                            expectedRevision = expectedRevision,
                            currentRevision = session.queueRevision,
                            desiredUri = session.desiredUri,
                            appliedUri = session.appliedUri,
                            phase = session.phase,
                        )
                    }
                }
                if (runtimeConfirmed != null) {
                    WallpaperDebugLog.i(
                        "playback_repo",
                        "active_apply_coalesce_runtime_confirmed",
                        "command" to WallpaperDebugLog.command(command.id),
                        "target" to WallpaperDebugLog.uri(uri),
                        "revision" to runtimeConfirmed.queueRevision,
                    )
                    if (commitTerminalApplyIfStillVisible(command, expectedRevision)) {
                        return@launch
                    }
                    WallpaperDebugLog.w(
                        "playback_repo",
                        "active_apply_coalesce_state_changed_fallback",
                        "command" to WallpaperDebugLog.command(command.id),
                        "target" to WallpaperDebugLog.uri(uri),
                        "expectedRevision" to expectedRevision,
                    )
                } else {
                    WallpaperDebugLog.w(
                        "playback_repo",
                        "active_apply_coalesce_timeout_fallback",
                        "command" to WallpaperDebugLog.command(command.id),
                        "target" to WallpaperDebugLog.uri(uri),
                        "expectedRevision" to expectedRevision,
                        "timeoutMs" to ACTIVE_APPLY_COALESCE_TIMEOUT_MS,
                    )
                }
            }
            submit(command)
        }
    }

    fun submitNextAsync(source: String = "automation") {
        scope.launch { submitNext(source) }
    }

    suspend fun submit(command: PlaybackCommand): PlaybackCommand =
        PlaybackTrace.suspendSection("command.submit") { submitInternal(command) }

    private suspend fun submitInternal(command: PlaybackCommand): PlaybackCommand {
        WallpaperDebugLog.i(
            "playback_repo",
            "command_submit_start",
            "command" to WallpaperDebugLog.command(command.id),
            "type" to command.type,
            "target" to WallpaperDebugLog.uri(command.targetUri),
            "source" to command.source,
            "requestedRevision" to command.playlistRevision,
        )
        val db = AppDatabase.getInstance(context)
        var storedCommand = command
        db.withTransaction {
            val previous = dao.getSession().toModel()
            val currentRevision = dao.getPlaylistState()?.revision ?: previous.queueRevision
            storedCommand = command.copy(
                playlistRevision = PlaybackStatePolicy.effectiveCommandRevision(
                    command.playlistRevision,
                    currentRevision,
                )
            )
            dao.insertCommand(
                PlaybackCommandEntity(
                    id = storedCommand.id,
                    type = storedCommand.type.name,
                    targetUri = storedCommand.targetUri,
                    playlistRevision = storedCommand.playlistRevision,
                    requestedAt = storedCommand.requestedAt,
                    source = storedCommand.source,
                )
            )
            // Commands are superseding state, not an audit log. Keep the table hard-bounded even
            // when the wallpaper service is inactive and therefore cannot terminalize requests.
            dao.deleteOtherCommands(storedCommand.id)
            dao.upsertSession(
                previous.copy(
                    desiredUri = storedCommand.targetUri ?: previous.desiredUri,
                    commandId = storedCommand.id,
                    phase = PlaybackPhase.REQUESTED,
                    errorCode = null,
                    errorMessage = null,
                    updatedAt = command.requestedAt,
                ).toEntity()
            )
            WallpaperDebugLog.i(
                "playback_repo",
                "command_submit_committed",
                "command" to WallpaperDebugLog.command(storedCommand.id),
                "type" to storedCommand.type,
                "target" to WallpaperDebugLog.uri(storedCommand.targetUri),
                "revision" to storedCommand.playlistRevision,
                "previousCommand" to WallpaperDebugLog.command(previous.commandId),
                "previousPhase" to previous.phase,
                "previousDesired" to WallpaperDebugLog.uri(previous.desiredUri),
                "previousApplied" to WallpaperDebugLog.uri(previous.appliedUri),
            )
        }
        return storedCommand
    }

    private suspend fun commitTerminalApplyIfStillVisible(
        command: PlaybackCommand,
        expectedRevision: Long,
    ): Boolean {
        val targetUri = command.targetUri ?: return false
        val db = AppDatabase.getInstance(context)
        var committed = false
        db.withTransaction {
            val current = dao.getSession().toModel()
            val currentRevision = dao.getPlaylistState()?.revision ?: current.queueRevision
            if (!ActiveApplyCommandPolicy.canCommitTerminalApply(
                    source = command.source,
                    targetUri = targetUri,
                    expectedRevision = expectedRevision,
                    currentRevision = currentRevision,
                    desiredUri = current.desiredUri,
                    appliedUri = current.appliedUri,
                    phase = current.phase,
                )
            ) {
                return@withTransaction
            }
            val storedCommand = command.copy(
                playlistRevision = PlaybackStatePolicy.effectiveCommandRevision(
                    command.playlistRevision,
                    currentRevision,
                )
            )
            dao.insertCommand(
                PlaybackCommandEntity(
                    id = storedCommand.id,
                    type = storedCommand.type.name,
                    targetUri = storedCommand.targetUri,
                    playlistRevision = storedCommand.playlistRevision,
                    requestedAt = storedCommand.requestedAt,
                    source = storedCommand.source,
                )
            )
            dao.upsertSession(
                current.copy(
                    desiredUri = targetUri,
                    appliedUri = targetUri,
                    commandId = storedCommand.id,
                    phase = PlaybackPhase.APPLIED,
                    queueRevision = currentRevision,
                    errorCode = null,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                ).toEntity()
            )
            dao.deleteOtherCommands(storedCommand.id)
            committed = true
            WallpaperDebugLog.i(
                "playback_repo",
                "active_apply_coalesced_committed",
                "command" to WallpaperDebugLog.command(storedCommand.id),
                "target" to WallpaperDebugLog.uri(targetUri),
                "revision" to currentRevision,
                "expectedRevision" to expectedRevision,
                "index" to current.currentIndex,
            )
        }
        return committed
    }

    suspend fun markPhase(
        commandId: String,
        phase: PlaybackPhase,
        desiredUri: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val current = dao.getSession().toModel()
            val accepted = PlaybackStatePolicy.acceptsPhaseUpdate(
                currentCommandId = current.commandId,
                currentPhase = current.phase,
                commandId = commandId,
                nextPhase = phase,
            )
            if (!accepted) {
                WallpaperDebugLog.w(
                    "playback_repo",
                    "phase_update_rejected",
                    "command" to WallpaperDebugLog.command(commandId),
                    "nextPhase" to phase,
                    "currentCommand" to WallpaperDebugLog.command(current.commandId),
                    "currentPhase" to current.phase,
                    "desired" to WallpaperDebugLog.uri(current.desiredUri),
                    "applied" to WallpaperDebugLog.uri(current.appliedUri),
                )
                return@withTransaction
            }
            dao.upsertSession(
                current.copy(
                    desiredUri = desiredUri ?: current.desiredUri,
                    commandId = commandId,
                    phase = phase,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                    updatedAt = System.currentTimeMillis(),
                ).toEntity()
            )
            WallpaperDebugLog.i(
                "playback_repo",
                "phase_update_committed",
                "command" to WallpaperDebugLog.command(commandId),
                "from" to current.phase,
                "to" to phase,
                "desired" to WallpaperDebugLog.uri(desiredUri ?: current.desiredUri),
                "applied" to WallpaperDebugLog.uri(current.appliedUri),
                "errorCode" to errorCode,
            )
            if (phase == PlaybackPhase.FAILED) dao.deleteOtherCommands(commandId)
        }
    }

    suspend fun markApplied(commandId: String, uri: String, currentIndex: Int, queueRevision: Long) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val current = dao.getSession().toModel()
            if (!PlaybackStatePolicy.acceptsAppliedRevision(current.queueRevision, queueRevision)) {
                WallpaperDebugLog.w(
                    "playback_repo",
                    "applied_rejected_revision",
                    "command" to WallpaperDebugLog.command(commandId),
                    "target" to WallpaperDebugLog.uri(uri),
                    "currentRevision" to current.queueRevision,
                    "appliedRevision" to queueRevision,
                )
                return@withTransaction
            }
            if (current.commandId != null && current.commandId != commandId) {
                WallpaperDebugLog.w(
                    "playback_repo",
                    "applied_rejected_stale_command",
                    "command" to WallpaperDebugLog.command(commandId),
                    "currentCommand" to WallpaperDebugLog.command(current.commandId),
                    "target" to WallpaperDebugLog.uri(uri),
                    "currentPhase" to current.phase,
                )
                return@withTransaction
            }

            val physicalQueue = wallpaperDao.playlistUriSnapshot()
            val physicalIndex = physicalQueue.indexOf(uri).takeIf { it >= 0 } ?: currentIndex
            dao.upsertSession(
                current.copy(
                    desiredUri = uri,
                    appliedUri = uri,
                    commandId = commandId,
                    phase = PlaybackPhase.APPLIED,
                    queueRevision = queueRevision,
                    currentIndex = physicalIndex,
                    // Traversal policy will publish the correct next after observing this visible URI.
                    nextPreviewUri = null,
                    errorCode = null,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                ).toEntity()
            )
            WallpaperDebugLog.i(
                "playback_repo",
                "applied_committed",
                "command" to WallpaperDebugLog.command(commandId),
                "target" to WallpaperDebugLog.uri(uri),
                "index" to physicalIndex,
                "rendererIndex" to currentIndex,
                "revision" to queueRevision,
            )
            dao.getPlaylistState()?.let { state ->
                dao.upsertPlaylistState(
                    state.toEntity(
                        currentUri = uri,
                        currentIndex = physicalIndex,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            dao.deleteOtherCommands(commandId)
        }
    }

    /** Publishes the requested runtime position without claiming that it is visible yet. */
    suspend fun updateRequestedPosition(
        currentUri: String,
        currentIndex: Int,
        nextPreviewUri: String? = null,
    ) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val current = dao.getSession().toModel()
            if (current.phase in setOf(
                    PlaybackPhase.REQUESTED,
                    PlaybackPhase.RESOLVING,
                    PlaybackPhase.PREPARING,
                    PlaybackPhase.READY,
                    PlaybackPhase.TRANSITIONING,
                ) && current.desiredUri != null && current.desiredUri != currentUri
            ) {
                WallpaperDebugLog.w(
                    "playback_repo",
                    "requested_position_rejected_pending_command",
                    "requested" to WallpaperDebugLog.uri(currentUri),
                    "desired" to WallpaperDebugLog.uri(current.desiredUri),
                    "applied" to WallpaperDebugLog.uri(current.appliedUri),
                    "command" to WallpaperDebugLog.command(current.commandId),
                    "phase" to current.phase,
                )
                return@withTransaction
            }
            val revision = dao.getPlaylistState()?.revision ?: current.queueRevision
            val physicalQueue = wallpaperDao.playlistUriSnapshot()
            val physicalIndex = physicalQueue.indexOf(currentUri).takeIf { it >= 0 } ?: currentIndex
            val physicalNext = physicalQueue.takeIf { it.size > 1 && physicalIndex in it.indices }
                ?.get((physicalIndex + 1) % physicalQueue.size)
                ?: nextPreviewUri?.takeIf { it != currentUri }
            dao.upsertSession(
                current.copy(
                    desiredUri = currentUri,
                    queueRevision = revision,
                    currentIndex = physicalIndex,
                    nextPreviewUri = physicalNext,
                    updatedAt = System.currentTimeMillis(),
                ).toEntity()
            )
            WallpaperDebugLog.d(
                "playback_repo",
                "requested_position_committed",
                "requested" to WallpaperDebugLog.uri(currentUri),
                "index" to physicalIndex,
                "rendererIndex" to currentIndex,
                "next" to WallpaperDebugLog.uri(physicalNext),
                "command" to WallpaperDebugLog.command(current.commandId),
                "phase" to current.phase,
            )
        }
    }

    /** Commits an automatic rotation only after the renderer has produced a visible frame. */
    suspend fun markRuntimeApplied(
        uri: String,
        currentIndex: Int,
        nextPreviewUri: String? = null,
    ) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val current = dao.getSession().toModel()
            if (!PlaybackRuntimePolicy.canCommitRuntimePosition(current.commandId, current.phase)) {
                WallpaperDebugLog.w(
                    "playback_repo",
                    "runtime_applied_rejected_pending_command",
                    "target" to WallpaperDebugLog.uri(uri),
                    "command" to WallpaperDebugLog.command(current.commandId),
                    "phase" to current.phase,
                    "desired" to WallpaperDebugLog.uri(current.desiredUri),
                    "applied" to WallpaperDebugLog.uri(current.appliedUri),
                )
                return@withTransaction
            }
            val stable = dao.getPlaylistState()
            val revision = stable?.revision ?: current.queueRevision
            val physicalQueue = wallpaperDao.playlistUriSnapshot()
            val physicalIndex = physicalQueue.indexOf(uri).takeIf { it >= 0 } ?: currentIndex
            dao.upsertSession(
                current.copy(
                    desiredUri = uri,
                    appliedUri = uri,
                    phase = PlaybackPhase.APPLIED,
                    queueRevision = revision,
                    currentIndex = physicalIndex,
                    // Do not publish a guessed future target here. The runtime coordinator owns it.
                    nextPreviewUri = null,
                    errorCode = null,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                ).toEntity()
            )
            WallpaperDebugLog.i(
                "playback_repo",
                "runtime_applied_committed",
                "target" to WallpaperDebugLog.uri(uri),
                "index" to physicalIndex,
                "rendererIndex" to currentIndex,
                "rendererNext" to WallpaperDebugLog.uri(nextPreviewUri),
                "previousDesired" to WallpaperDebugLog.uri(current.desiredUri),
                "previousApplied" to WallpaperDebugLog.uri(current.appliedUri),
            )
            stable?.let { state ->
                dao.upsertPlaylistState(
                    state.toEntity(
                        currentUri = uri,
                        currentIndex = physicalIndex,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    suspend fun syncPlaylist(queue: List<String>, currentUri: String? = null): Long {
        val db = AppDatabase.getInstance(context)
        return db.withTransaction {
            val previous = dao.getPlaylistState()
            val fingerprint = PlaybackQueueStoragePolicy.fingerprint(queue)
            val revision = PlaybackStatePolicy.nextPlaylistRevision(
                previous?.revision,
                previous?.queueFingerprint,
                fingerprint,
            )
            // A cursor is meaningful only while its URI is still a member of this concrete queue.
            val requestedCurrent = currentUri?.takeIf { it in queue }
            val retainedCurrent = previous?.currentUri?.takeIf { it in queue }
            val resolvedUri = requestedCurrent ?: retainedCurrent
            val resolvedIndex = queue.indexOf(resolvedUri)
            dao.upsertPlaylistState(
                PlaylistStateEntity(
                    revision = revision,
                    queueJson = PlaybackQueueStoragePolicy.legacyQueueJson(queue),
                    queueFingerprint = fingerprint,
                    currentUri = resolvedUri,
                    currentIndex = resolvedIndex,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            val session = dao.getSession().toModel()
            // Queue/membership changes are not playback commands. Only submit(APPLY/NEXT/...)
            // owns REQUESTED -> PREPARING -> ... phase transitions.
            dao.upsertSession(
                session.copy(
                    desiredUri = requestedCurrent ?: session.desiredUri,
                    phase = session.phase,
                    queueRevision = revision,
                    queue = emptyList(),
                    shuffleRound = emptyList(),
                    currentIndex = resolvedIndex,
                    nextPreviewUri = session.nextPreviewUri?.takeIf { it in queue && it != resolvedUri },
                    updatedAt = System.currentTimeMillis(),
                ).toEntity()
            )
            WallpaperDebugLog.i(
                "playback_repo",
                "playlist_sync_committed",
                "queueSize" to queue.size,
                "current" to WallpaperDebugLog.uri(resolvedUri),
                "revision" to revision,
                "previousDesired" to WallpaperDebugLog.uri(session.desiredUri),
                "previousApplied" to WallpaperDebugLog.uri(session.appliedUri),
                "retainedCommand" to WallpaperDebugLog.command(session.commandId),
                "phaseFrom" to session.phase,
                "phaseTo" to session.phase,
            )
            revision
        }
    }

    /** Updates runtime playback position without changing the user's stable queue revision. */
    suspend fun updatePlaybackPosition(
        currentUri: String?,
        currentIndex: Int,
        nextPreviewUri: String? = null,
    ) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val stable = dao.getPlaylistState()
            val session = dao.getSession().toModel()
            val revision = stable?.revision ?: session.queueRevision
            val physicalQueue = wallpaperDao.playlistUriSnapshot()
            val physicalIndex = currentUri?.let(physicalQueue::indexOf)?.takeIf { it >= 0 } ?: currentIndex
            dao.upsertSession(
                session.copy(
                    desiredUri = currentUri ?: session.desiredUri,
                    appliedUri = currentUri ?: session.appliedUri,
                    queueRevision = revision,
                    queue = emptyList(),
                    currentIndex = physicalIndex,
                    shuffleRound = emptyList(),
                    nextPreviewUri = nextPreviewUri?.takeIf { it != currentUri && it in physicalQueue },
                    updatedAt = System.currentTimeMillis(),
                ).toEntity()
            )
            stable?.let {
                dao.upsertPlaylistState(
                    it.toEntity(
                        currentUri = currentUri,
                        currentIndex = physicalIndex,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    suspend fun playlistRevision(): Long = dao.getPlaylistState()?.revision ?: 0L

    private fun PlaybackSessionRecord?.toModel(): PlaybackSession {
        if (this == null) return PlaybackSession()
        return PlaybackSession(
            desiredUri = desiredUri,
            appliedUri = appliedUri,
            commandId = commandId,
            phase = runCatching { PlaybackPhase.valueOf(phase) }.getOrDefault(PlaybackPhase.IDLE),
            queueRevision = queueRevision,
            queue = emptyList(),
            currentIndex = currentIndex,
            shuffleRound = emptyList(),
            nextPreviewUri = nextPreviewUri,
            errorCode = errorCode,
            errorMessage = errorMessage,
            updatedAt = updatedAt,
        )
    }

    private fun PlaybackSession.toEntity() = PlaybackSessionEntity(
        desiredUri = desiredUri,
        appliedUri = appliedUri,
        commandId = commandId,
        phase = phase.name,
        queueRevision = queueRevision,
        queueJson = PlaybackQueueStoragePolicy.legacyQueueJson(queue),
        currentIndex = currentIndex,
        shuffleRoundJson = PlaybackQueueStoragePolicy.legacyShuffleRoundJson(shuffleRound),
        nextPreviewUri = nextPreviewUri,
        errorCode = errorCode,
        errorMessage = errorMessage,
        updatedAt = updatedAt,
    )

    private fun PlaylistStateRecord.toSnapshot(queue: List<String>) = PlaylistStateSnapshot(
        revision = revision,
        queue = queue,
        currentUri = currentUri?.takeIf { it in queue },
        currentIndex = queue.indexOf(currentUri).takeIf { it >= 0 } ?: -1,
    )

    private fun PlaylistStateRecord.toEntity(
        currentUri: String? = this.currentUri,
        currentIndex: Int = this.currentIndex,
        updatedAt: Long = this.updatedAt,
    ) = PlaylistStateEntity(
        revision = revision,
        queueJson = PlaybackQueueStoragePolicy.legacyQueueJson(emptyList()),
        queueFingerprint = queueFingerprint,
        currentUri = currentUri,
        currentIndex = currentIndex,
        updatedAt = updatedAt,
    )

    companion object {
        private const val ACTIVE_APPLY_COALESCE_TIMEOUT_MS = 2_000L

        @Volatile private var INSTANCE: PlaybackRepository? = null

        fun getInstance(context: Context): PlaybackRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: PlaybackRepository(
                context.applicationContext,
                AppDatabase.getInstance(context).playbackDao(),
                AppDatabase.getInstance(context).wallpaperDao(),
            ).also { INSTANCE = it }
        }
    }
}
