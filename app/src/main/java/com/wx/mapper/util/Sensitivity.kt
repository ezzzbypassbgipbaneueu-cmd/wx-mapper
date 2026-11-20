package com.wx.mapper.util

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Sensibilidade do mouse. O slider é LOGARÍTMICO: a metade de baixo do slider cobre os valores
 * pequenos (1% a ~17%) com precisão e o resto vai até 300%. Antes era linear de 5% a 300%, então
 * 20% já ficava colado no começo do slider e não dava para ajustar fino.
 * O valor salvo continua sendo o mesmo número (0.2 = 20%), então perfis antigos não mudam.
 */
object Sensitivity {
    const val MIN = 0.01f
    const val MAX = 3.0f

    private val span = ln(MAX / MIN)

    fun toSlider(value: Float): Float =
        (ln(value.coerceIn(MIN, MAX) / MIN) / span).coerceIn(0f, 1f)

    fun fromSlider(t: Float): Float {
        val v = MIN * (MAX / MIN).pow(t.coerceIn(0f, 1f))
        // Arredonda para o passo mostrado: 0,1% abaixo de 10%, 1% acima.
        return if (v < 0.1f) (v * 1000f).roundToInt() / 1000f else (v * 100f).roundToInt() / 100f
    }

    fun format(value: Float): String {
        val pct = value * 100f
        return if (pct < 10f) "${"%.1f".format(pct)} %" else "${pct.roundToInt()} %"
    }
}
