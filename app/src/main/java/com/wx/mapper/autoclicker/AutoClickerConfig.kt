package com.wx.mapper.autoclicker

import com.wx.mapper.domain.KEYCODE_RMC
import kotlinx.serialization.Serializable

@Serializable
enum class ClickPositionMode {
    /** Clica onde está o cursor virtual do mouse. */
    FOLLOW_POINTER,

    /** Clica sempre na coordenada fixa (fixedX, fixedY), em pixels da tela. */
    FIXED
}

@Serializable
data class AutoClickerConfig(
    val enabled: Boolean = true,
    /** Cliques por segundo. Sempre limitado a [MIN_CPS]..[MAX_CPS]. */
    val cps: Float = DEFAULT_CPS,
    /** Botão do mouse (KEYCODE_LMC/RMC/MMC) ou tecla que ativa o autoclicker enquanto pressionado. */
    val triggerKeyCode: Int = KEYCODE_RMC,
    val positionMode: ClickPositionMode = ClickPositionMode.FOLLOW_POINTER,
    val fixedX: Float = 0f,
    val fixedY: Float = 0f,
    /**
     * false (padrão) = SEGURAR: só clica enquanto o botão estiver pressionado e para ao soltar.
     * true = ALTERNAR: um clique no botão liga o autoclicker, outro clique desliga.
     */
    val toggleMode: Boolean = false,
) {
    fun sanitized(): AutoClickerConfig = copy(cps = cps.coerceIn(MIN_CPS, MAX_CPS))

    companion object {
        const val MIN_CPS = 1f
        const val MAX_CPS = 50f
        const val DEFAULT_CPS = 30f
    }
}
