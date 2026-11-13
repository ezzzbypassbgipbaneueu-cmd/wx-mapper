package com.wx.mapper.input

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

    fun attach(actions: WxActions) {
        _actions.value = actions
    }

    fun detach(actions: WxActions) {
        if (_actions.value === actions) _actions.value = null
    }
}
