package com.wx.mapper.macros

import androidx.compose.ui.geometry.Offset
import com.wx.mapper.model.EventInjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val MACRO_POINTER_ID = 21

/** Reproduz uma macro por vez. Interrupção sempre solta o ponteiro. */
class MacroPlayer(
    private val scope: CoroutineScope,
    private val injector: EventInjector,
    private val pointerId: Int = MACRO_POINTER_ID,
) {
    private var job: Job? = null
    private val _playing = MutableStateFlow<Int?>(null)

    /** Id da macro em reprodução, ou null. */
    val playingMacroId: StateFlow<Int?> = _playing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Retorna false (e preenche [lastError]) se a macro for inválida. */
    @Synchronized
    fun play(macro: Macro): Boolean {
        val error = macro.validate()
        if (error != null) {
            _lastError.value = error
            return false
        }
        stop()
        _lastError.value = null
        _playing.value = macro.id
        job = scope.launch {
            try {
                do {
                    for (action in macro.actions) {
                        if (!isActive) break
                        when (action) {
                            is MacroAction.Tap -> {
                                injector.injectPointer(pointerId, Offset(action.x, action.y))
                                delay(action.holdMs.coerceAtLeast(Macro.MIN_STEP_MS))
                                injector.releasePointer(pointerId)
                            }

                            is MacroAction.Wait -> delay(action.ms.coerceAtLeast(Macro.MIN_STEP_MS))
                        }
                    }
                } while (macro.loop && isActive)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = "Falha ao reproduzir: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                injector.releasePointer(pointerId)
                _playing.value = null
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Se esta macro já está tocando, para; senão inicia. */
    fun toggle(macro: Macro) {
        if (_playing.value == macro.id) stop() else play(macro)
    }
}
