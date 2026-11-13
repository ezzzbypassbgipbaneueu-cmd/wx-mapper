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
 * Autoclicker com um único worker. start() ativa ou retoma; stop() pausa sem
 * cancelar/criar workers concorrentes. Isso evita DOWN/UP de ciclos antigos
 * disputarem o mesmo pointerId quando o usuário pausa e retoma rapidamente.
 */
class AutoClicker(
    private val scope: CoroutineScope,
    private val injector: EventInjector,
    private val pointerId: Int = AUTOCLICKER_POINTER_ID,
) {
    private var worker: Job? = null
    @Volatile private var currentConfig: AutoClickerConfig = AutoClickerConfig()
    @Volatile private var currentPosition: (() -> Offset)? = null

    private val _running = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _running.asStateFlow()

    @Synchronized
    fun start(config: AutoClickerConfig, position: () -> Offset) {
        currentConfig = config.sanitized()
        currentPosition = position
        _running.value = true
        if (worker?.isActive == true) return

        worker = scope.launch {
            try {
                while (isActive) {
                    if (!_running.value) {
                        delay(IDLE_POLL_MS)
                        continue
                    }

                    val c = currentConfig
                    val periodMs = (1000f / c.cps).toLong().coerceAtLeast(1L)
                    val holdMs = (periodMs / 3).coerceIn(1L, MAX_HOLD_MS)
                    val restMs = (periodMs - holdMs).coerceAtLeast(1L)
                    val position = currentPosition?.invoke() ?: Offset.Zero

                    injector.injectPointer(pointerId, position)
                    delay(holdMs)
                    injector.releasePointer(pointerId)
                    delay(restMs)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _running.value = false
            } finally {
                injector.releasePointer(pointerId)
                _running.value = false
            }
        }
    }

    /** Pausa/para imediatamente e garante que o toque atual seja solto. */
    @Synchronized
    fun stop() {
        _running.value = false
        injector.releasePointer(pointerId)
    }

    private companion object {
        const val MAX_HOLD_MS = 15L
        const val IDLE_POLL_MS = 8L
    }
}
