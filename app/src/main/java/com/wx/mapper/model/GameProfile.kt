package com.wx.mapper.model

import com.wx.mapper.autoclicker.AutoClickerConfig
import com.wx.mapper.macros.Macro
import kotlinx.serialization.Serializable

/**
 * Perfil de UM jogo. Cada jogo guarda o seu próprio keymap (botões, em buttons_config_<pacote>),
 * e aqui ficam o autoclicker, as macros, a sensibilidade do mouse e a opacidade dos botões.
 * Trocar de jogo troca tudo isso junto, então um jogo nunca bagunça o outro.
 */
@Serializable
data class GameProfile(
    val autoClicker: AutoClickerConfig = AutoClickerConfig(),
    val macros: List<Macro> = emptyList(),
    val pointerSensitivity: Float = DEFAULT_SENSITIVITY,
    val overlayOpacity: Float = DEFAULT_OPACITY,
) {
    companion object {
        const val DEFAULT_SENSITIVITY = 0.5f
        const val DEFAULT_OPACITY = 0.5f
    }
}

/** Resumo de um jogo para mostrar na lista (não é salvo, é calculado do que está salvo). */
data class GameSummary(
    val buttons: Int = 0,
    val macros: Int = 0,
    val cps: Float = 0f,
    val autoClickerEnabled: Boolean = false,
)
