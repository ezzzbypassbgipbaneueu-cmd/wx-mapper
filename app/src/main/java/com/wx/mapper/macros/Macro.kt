package com.wx.mapper.macros

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class MacroAction {
    /** Toque em (x, y) segurando por [holdMs]. */
    @Serializable
    @SerialName("tap")
    data class Tap(val x: Float, val y: Float, val holdMs: Long = 30L) : MacroAction()

    /** Espera [ms] antes da próxima ação. */
    @Serializable
    @SerialName("wait")
    data class Wait(val ms: Long) : MacroAction()
}

@Serializable
data class Macro(
    val id: Int,
    val name: String,
    /** Tecla/botão que dispara a macro (aperta = inicia, aperta de novo = para). Null = só pelo botão Reproduzir. */
    val triggerKeyCode: Int? = null,
    /** Repete até ser interrompida. */
    val loop: Boolean = false,
    val actions: List<MacroAction> = emptyList(),
) {
    /** Duração de um ciclo, em ms (soma de holds e esperas, já com os mínimos aplicados). */
    val cycleMs: Long
        get() = actions.sumOf {
            when (it) {
                is MacroAction.Tap -> it.holdMs.coerceAtLeast(MIN_STEP_MS)
                is MacroAction.Wait -> it.ms.coerceAtLeast(MIN_STEP_MS)
            }
        }

    /** Mensagem de erro ou null se a macro é válida. */
    fun validate(): String? = when {
        name.isBlank() -> "A macro precisa de um nome."
        actions.isEmpty() -> "Adicione ao menos uma ação."
        actions.size > MAX_ACTIONS -> "Máximo de $MAX_ACTIONS ações por macro."
        loop && cycleMs < MIN_LOOP_CYCLE_MS ->
            "Macro em loop precisa de pelo menos $MIN_LOOP_CYCLE_MS ms por ciclo."
        else -> null
    }

    companion object {
        const val MAX_ACTIONS = 200
        const val MIN_STEP_MS = 1L
        const val MIN_LOOP_CYCLE_MS = 20L
    }
}
