package com.wallpaperhub.preview.model

import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.playback.model.PlaybackSession

enum class PreviewApplyState {
    IDLE,
    SAVING,
    SAVED,
    OPENING_SYSTEM_CONFIRMATION,
    WAITING_FOR_SYSTEM_CONFIRMATION,
    VERIFYING_APPLIED_WALLPAPER,
    APPLIED,
    ERROR,
}

enum class ApplyLaunchStep {
    COMMAND_SUBMITTED,
    SYSTEM_CONFIRMATION_REQUIRED,
    FAILED,
}

object PreviewApplyPolicy {
    fun afterLaunch(step: ApplyLaunchStep): PreviewApplyState = when (step) {
        ApplyLaunchStep.COMMAND_SUBMITTED -> PreviewApplyState.VERIFYING_APPLIED_WALLPAPER
        ApplyLaunchStep.SYSTEM_CONFIRMATION_REQUIRED -> PreviewApplyState.WAITING_FOR_SYSTEM_CONFIRMATION
        ApplyLaunchStep.FAILED -> PreviewApplyState.ERROR
    }

    fun afterSystemReturn(serviceActive: Boolean): PreviewApplyState = if (serviceActive) {
        PreviewApplyState.VERIFYING_APPLIED_WALLPAPER
    } else {
        PreviewApplyState.ERROR
    }

    /** Returns null when a playback update belongs to another command or wallpaper. */
    fun fromSession(targetUri: String, session: PlaybackSession): PreviewApplyState? {
        if (session.desiredUri != targetUri) return null
        return when {
            session.phase == PlaybackPhase.APPLIED && session.appliedUri == targetUri -> PreviewApplyState.APPLIED
            session.phase == PlaybackPhase.FAILED -> PreviewApplyState.ERROR
            session.phase in VERIFYING_PHASES -> PreviewApplyState.VERIFYING_APPLIED_WALLPAPER
            else -> null
        }
    }

    private val VERIFYING_PHASES = setOf(
        PlaybackPhase.REQUESTED,
        PlaybackPhase.RESOLVING,
        PlaybackPhase.PREPARING,
        PlaybackPhase.READY,
        PlaybackPhase.TRANSITIONING,
    )
}
