package com.wx.mapper.autoclicker

import android.os.Process
import androidx.compose.ui.geometry.Offset
import com.wx.mapper.model.EventInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

const val AUTOCLICKER_POINTER_ID = 20

/** Thread única de prioridade alta, compartilhada: os toques do autoclicker/macros não disputam CPU. */
val WxHighPriorityDispatcher: CoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { r ->
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Exception) {
            }
            r.run()
        }, "wx-autoclicker").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/**
 * Autoclicker com um único worker. start() ativa ou retoma; stop() pausa sem
 * cancelar/criar workers concorrentes. Isso evita DOWN/UP de ciclos antigos
 * disputarem o mesmo pointerId quando o usuário pausa e retoma rapidamente.
 *
 * Temporização:
 *  - Roda no escopo do WxActions, que usa a thread de prioridade alta [WxHighPriorityDispatcher].
 *  - Cronograma absoluto (próximo clique = anterior + período): sem acumular atraso, CPS fiel.
 *  - Retomar (ex.: soltar o botão esquerdo) acorda o worker na hora e o 1º clique sai imediatamente,
 *    sem esperar o antigo "poll" de 8 ms.
 */
class AutoClicker(
    private val scope: CoroutineScope,
    private val injector: EventInjector,
    private val pointerId: Int = AUTOCLICKER_POINTER_ID,
    /** Relógio em nanossegundos (injetável para testes com tempo virtual). */
    private val clock: () -> Long = System::nanoTime,
) {
    private var worker: Job? = null
    @Volatile private var currentConfig: AutoClickerConfig = AutoClickerConfig()
    @Volatile private var currentPosition: (() -> Offset)? = null

    private val _running = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _running.asStateFlow()

    /** Acorda o worker quando ele está parado esperando. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start(config: AutoClickerConfig, position: () -> Offset) {
        currentConfig = config.sanitized()
        currentPosition = position
        _running.value = true
        wake.trySend(Unit)
        if (worker?.isActive == true) return

        worker = scope.launch {
            try {
                while (isActive) {
                    if (!_running.value) {
                        wake.receive()
                        continue
                    }

                    // Cronograma absoluto: o 1º clique sai agora.
                    var next = clock()
                    while (isActive && _running.value) {
                        waitUntil(next)
                        if (!_running.value) break

                        val c = currentConfig
                        val periodNs = (1_000_000_000.0 / c.cps).toLong().coerceAtLeast(1_000_000L)
                        // O toque precisa durar o que foi configurado (jogos que leem o toque 1x por frame
                        // perdem toques curtos demais), deixando só um respiro mínimo para soltar.
                        val holdNs = (c.holdMs * 1_000_000L).coerceIn(0L, (periodNs - MIN_REST_NS).coerceAtLeast(0L))

                        val position = currentPosition?.invoke() ?: Offset.Zero
                        if (holdNs == 0L) {
                            // Toque instantâneo: DOWN+UP atômicos (mesmo timestamp, sem esperar o sistema).
                            injector.tap(pointerId, position)
                        } else {
                            val downAt = clock()
                            injector.injectPointer(pointerId, position)
                            waitUntil(downAt + holdNs)
                            injector.releasePointer(pointerId)
                        }

                        next += periodNs
                        // Se atrasou muito (CPU ocupada), não "recupera" disparando em rajada.
                        val now = clock()
                        if (next < now - periodNs) next = now
                    }
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

    /** Espera até o instante [targetNs] (relógio do [clock]); nunca antes, no máximo ~1 ms depois. */
    private suspend fun waitUntil(targetNs: Long) {
        val remaining = targetNs - clock()
        if (remaining > 0L) delay((remaining + 999_999L) / 1_000_000L)
    }

    private companion object {
        const val MIN_REST_NS = 3_000_000L
    }
}
