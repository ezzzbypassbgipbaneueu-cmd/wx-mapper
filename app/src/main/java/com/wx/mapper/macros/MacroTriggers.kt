package com.wx.mapper.macros

import android.view.KeyEvent

/** Escolhe uma tecla de atalho livre para uma macro recém-gravada, sem o usuário configurar nada. */
object MacroTriggers {
    private val CANDIDATES = listOf(
        KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F12,
        KeyEvent.KEYCODE_F11,
    )

    /** Primeira tecla F livre (F7, F6, F5…) que não está em [used]; null se todas estiverem ocupadas. */
    fun nextFree(used: Set<Int>): Int? = CANDIDATES.firstOrNull { it !in used }
}
