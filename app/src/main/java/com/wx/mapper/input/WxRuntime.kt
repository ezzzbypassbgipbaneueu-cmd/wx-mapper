package com.wx.mapper.input

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ponte entre o serviço de overlay (que tem a conexão Shizuku e o injetor de eventos)
 * e as telas do app. Só há [WxActions] enquanto o serviço está rodando.
 */
object WxRuntime {
    private val _actions = MutableStateFlow<WxActions?>(null)
    val actions: StateFlow<WxActions?> = _actions.asStateFlow()

    /**
     * Posição do cursor virtual do overlay, em coordenadas da TELA, ou null quando o overlay não
     * está segurando o mouse (editando, digitando, modo tiro ou serviço parado).
     * Com a captura do ponteiro o app que está embaixo NUNCA recebe "mouse passando por cima"
     * (só toques injetados ao clicar), então telas como o treino de mira leem a posição por aqui.
     */
    @Volatile
    var cursorProvider: (() -> Offset?)? = null

    fun overlayCursor(): Offset? = try { cursorProvider?.invoke() } catch (_: Throwable) { null }

    fun attach(actions: WxActions) {
        _actions.value = actions
    }

    fun detach(actions: WxActions) {
        if (_actions.value === actions) _actions.value = null
    }
}
