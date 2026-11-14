package com.wx.mapper.macros

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Grava uma macro a partir do uso normal: cada toque (clique do mouse ou tecla mapeada)
 * vira uma ação [MacroAction.Tap] e o intervalo entre toques vira [MacroAction.Wait].
 */
class MacroRecorder(private val clock: () -> Long = { SystemClock.uptimeMillis() }) {
    private val actions = mutableListOf<MacroAction>()
    private var lastEnd = 0L
    private var downAt = 0L
    private var downPos: Offset? = null

    private val _recording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _recording.asStateFlow()

    @Synchronized
    fun start() {
        actions.clear()
        downPos = null
        lastEnd = clock()
        _recording.value = true
    }

    @Synchronized
    fun onDown(position: Offset) {
        if (!_recording.value) return
        finishTap()
        val now = clock()
        val gap = now - lastEnd
        if (actions.isNotEmpty() && gap >= MIN_WAIT_MS) add(MacroAction.Wait(gap))
        downAt = now
        downPos = position
    }

    @Synchronized
    fun onUp() {
        if (_recording.value) finishTap()
    }

    /** Para a gravação e devolve as ações gravadas (vazio se não estava gravando). */
    @Synchronized
    fun stop(): List<MacroAction> {
        if (!_recording.value) return emptyList()
        finishTap()
        _recording.value = false
        while (actions.lastOrNull() is MacroAction.Wait) actions.removeAt(actions.lastIndex)
        return actions.toList().also { actions.clear() }
    }

    private fun finishTap() {
        val p = downPos ?: return
        val now = clock()
        add(MacroAction.Tap(p.x, p.y, (now - downAt).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)))
        lastEnd = now
        downPos = null
    }

    private fun add(action: MacroAction) {
        if (actions.size < Macro.MAX_ACTIONS) actions += action
    }

    private companion object {
        const val MIN_WAIT_MS = 1L
        const val MIN_HOLD_MS = 1L
        const val MAX_HOLD_MS = 2000L
    }
}
