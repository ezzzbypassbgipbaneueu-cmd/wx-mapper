package com.wx.mapper.autoclicker

import androidx.compose.ui.geometry.Offset
import com.wx.mapper.model.EventInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val AUTOCLICKER_POINTER_ID = 20

/**
 * Dispara toques repetidos via [EventInjector] enquanto estiver ativo.
 * Um toque = DOWN, espera [holdMs], UP, espera o resto do período.
 * O cancelamento (stop) sempre solta o ponteiro no `finally`.
 */
class AutoClicker(
    private val scope: CoroutineScope,
    private val injector: EventInjector,
    private val pointerId: Int = AUTOCLICKER_POINTER_ID,
) {
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _running.asStateFlow()

    @Synchronized
    fun start(config: AutoClickerConfig, position: () -> Offset) {
        if (job?.isActive == true) return
        val c = config.sanitized()
        val periodMs = (1000f / c.cps).toLong().coerceAtLeast(1L)
        val holdMs = (periodMs / 3).coerceIn(1L, MAX_HOLD_MS)
        val restMs = (periodMs - holdMs).coerceAtLeast(1L)
        _running.value = true
        job = scope.launch {
            try {
                while (isActive) {
                    injector.injectPointer(pointerId, position())
                    delay(holdMs)
                    injector.releasePointer(pointerId)
                    delay(restMs)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Erro de injeção (ex.: Shizuku caiu): encerra sem derrubar o app.
            } finally {
                injector.releasePointer(pointerId)
                _running.value = false
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val MAX_HOLD_MS = 15L
    }
}
