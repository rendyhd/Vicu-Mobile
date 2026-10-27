package com.rendyhd.vicu.ui.screens.guided

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The guided sessions that ran to their end since the weekly review started, so its steps tick
 * themselves off (docs/cross-app-semantics-v1.md, section 14.7): `inbox`, `overdue`, `waiting`
 * and `review`.
 */
class GuidedProgress {
    private val _finished = MutableStateFlow<Set<String>>(emptySet())
    val finished: StateFlow<Set<String>> = _finished.asStateFlow()

    fun finished(step: String) = _finished.update { it + step }

    fun reset() {
        _finished.value = emptySet()
    }
}
