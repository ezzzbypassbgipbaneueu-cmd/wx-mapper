package com.wx.mapper.input

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.wx.mapper.autoclicker.AutoClicker
import com.wx.mapper.autoclicker.AutoClickerConfig
import com.wx.mapper.autoclicker.ClickPositionMode
import com.wx.mapper.domain.KEYCODE_LMC
import com.wx.mapper.domain.KEYCODE_MMC
import com.wx.mapper.domain.KEYCODE_RMC
import com.wx.mapper.macros.Macro
import com.wx.mapper.macros.MacroPlayer
import com.wx.mapper.model.EventInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Liga teclas/botões do mouse ao autoclicker e às macros.
 * Não conhece UI nem Shizuku: só recebe eventos de entrada e usa um [EventInjector].
 */
class WxActions(
    injector: EventInjector,
    private val pointerPosition: () -> Offset,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val autoClicker = AutoClicker(scope, injector)
    val macroPlayer = MacroPlayer(scope, injector)

    @Volatile
    var autoClickerConfig: AutoClickerConfig = AutoClickerConfig()
        set(value) {
            field = value.sanitized()
            // Desativar ou trocar o gatilho com o autoclicker rodando: para na hora.
            if (!field.enabled) autoClicker.stop()
        }

    @Volatile
    private var macrosByKey: Map<Int, Macro> = emptyMap()

    private val held = HashSet<Int>()

    fun setMacros(macros: List<Macro>) {
        macrosByKey = macros.filter { it.triggerKeyCode != null }
            .associateBy { it.triggerKeyCode!! }
    }

    /**
     * @return true se o evento foi consumido (é gatilho do autoclicker ou de uma macro).
     */
    @Synchronized
    fun onTrigger(keyCode: Int, pressed: Boolean): Boolean {
        val config = autoClickerConfig
        val isAuto = config.enabled && keyCode == config.triggerKeyCode
        val macro = macrosByKey[keyCode]
        if (!isAuto && macro == null) return false

        // Ignora auto-repeat do teclado: só a transição solto -> apertado conta.
        val transition = if (pressed) held.add(keyCode) else held.remove(keyCode)

        if (isAuto) {
            if (pressed) {
                if (transition) autoClicker.start(config) { clickPosition(config) }
            } else {
                autoClicker.stop() // soltou: para imediatamente
            }
        }
        if (macro != null && pressed && transition) macroPlayer.toggle(macro)
        return true
    }

    /**
     * Rede de segurança: se um evento de "soltar" se perder (foco, overlay),
     * o estado real dos botões do mouse (buttonState) manda parar.
     */
    @Synchronized
    fun syncMouseButtons(buttonState: Int) {
        val config = autoClickerConfig
        val mask = when (config.triggerKeyCode) {
            KEYCODE_LMC -> MotionEvent.BUTTON_PRIMARY
            KEYCODE_RMC -> MotionEvent.BUTTON_SECONDARY
            KEYCODE_MMC -> MotionEvent.BUTTON_TERTIARY
            else -> return
        }
        if (buttonState and mask == 0 && autoClicker.isRunning.value) {
            held.remove(config.triggerKeyCode)
            autoClicker.stop()
        }
    }

    @Synchronized
    fun stopAll() {
        held.clear()
        autoClicker.stop()
        macroPlayer.stop()
    }

    fun release() {
        stopAll()
        scope.cancel()
    }

    private fun clickPosition(config: AutoClickerConfig): Offset =
        if (config.positionMode == ClickPositionMode.FIXED) Offset(config.fixedX, config.fixedY)
        else pointerPosition()
}
