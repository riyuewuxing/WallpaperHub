package com.wallpaperhub.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local structured report for duplicate import decisions. */
object Round4DuplicateImportAudit {
    private val _report = MutableStateFlow<List<Round4ImportDeduplicator.DuplicateMatch>?>(null)
    val report: StateFlow<List<Round4ImportDeduplicator.DuplicateMatch>?> = _report.asStateFlow()

    fun publish(matches: List<Round4ImportDeduplicator.DuplicateMatch>) {
        _report.value = matches.takeIf { it.isNotEmpty() }
    }

    /** Remove only the reviewed row; large import reports remain available for the remaining decisions. */
    fun resolve(match: Round4ImportDeduplicator.DuplicateMatch) {
        val current = _report.value ?: return
        var removed = false
        val next = current.filterNot { candidate ->
            val same = !removed && candidate.sourceUri == match.sourceUri && candidate.sha256 == match.sha256
            if (same) removed = true
            same
        }
        _report.value = next.takeIf { it.isNotEmpty() }
    }

    fun dismiss() {
        _report.value = null
    }
}
