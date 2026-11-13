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

/** Liga teclas/botões físicos ao autoclicker e às macros. */
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
            val old = field
            field = value.sanitized()
            if (!field.enabled || old.triggerKeyCode != field.triggerKeyCode) {
                autoClickerToggled = false
                held.clear()
                autoClicker.stop()
            }
        }

    @Volatile
    private var macrosByKey: Map<Int, Macro> = emptyMap()

    private val held = HashSet<Int>()
    private var autoClickerToggled = false
    private var primaryButtonDown = false

    fun setMacros(macros: List<Macro>) {
        macrosByKey = macros.filter { it.triggerKeyCode != null }
            .associateBy { it.triggerKeyCode!! }
    }

    /**
     * RMC (botão direito) funciona como chave liga/desliga: um clique ativa,
     * outro clique desativa. Para outros gatilhos, mantém o comportamento hold.
     */
    @Synchronized
    fun onTrigger(keyCode: Int, pressed: Boolean): Boolean {
        val config = autoClickerConfig
        val isAuto = config.enabled && keyCode == config.triggerKeyCode
        val macro = macrosByKey[keyCode]
        if (!isAuto && macro == null) return false

        val transition = if (pressed) held.add(keyCode) else held.remove(keyCode)

        if (isAuto) {
            if (config.triggerKeyCode == KEYCODE_RMC) {
                if (pressed && transition) {
                    autoClickerToggled = !autoClickerToggled
                    if (autoClickerToggled && !primaryButtonDown) {
                        autoClicker.start(config) { clickPosition(config) }
                    } else {
                        autoClicker.stop()
                    }
                }
                // A soltura do botão direito não desliga o modo toggle.
            } else if (pressed) {
                if (transition && !primaryButtonDown) {
                    autoClicker.start(config) { clickPosition(config) }
                }
            } else {
                autoClicker.stop()
            }
        }

        if (macro != null && pressed && transition) macroPlayer.toggle(macro)
        return true
    }

    /**
     * O botão esquerdo suspende o autoclicker enquanto está pressionado
     * (para permitir arrastar/mover a câmera); ao soltar, ele retoma se o
     * modo toggle continuar ligado.
     */
    @Synchronized
    fun onPrimaryButton(pressed: Boolean) {
        if (primaryButtonDown == pressed) return
        primaryButtonDown = pressed
        val config = autoClickerConfig
        if (!config.enabled) return

        if (config.triggerKeyCode == KEYCODE_RMC) {
            if (autoClickerToggled) {
                if (pressed) autoClicker.stop()
                else autoClicker.start(config) { clickPosition(config) }
            }
        } else if (pressed && autoClicker.isRunning.value) {
            autoClicker.stop()
        } else if (!pressed && held.contains(config.triggerKeyCode)) {
            autoClicker.start(config) { clickPosition(config) }
        }
    }

    /** Sincroniza o estado do botão esquerdo caso algum evento de mouse se perca. */
    @Synchronized
    fun syncMouseButtons(buttonState: Int) {
        val primaryNow = buttonState and MotionEvent.BUTTON_PRIMARY != 0
        onPrimaryButton(primaryNow)

        val config = autoClickerConfig
        // O gatilho RMC é toggle: soltar o botão direito não deve pará-lo.
        if (config.triggerKeyCode == KEYCODE_RMC) return

        val mask = when (config.triggerKeyCode) {
            KEYCODE_LMC -> MotionEvent.BUTTON_PRIMARY
            KEYCODE_MMC -> MotionEvent.BUTTON_TERTIARY
            else -> return
        }
        if (buttonState and mask == 0 && held.contains(config.triggerKeyCode)) {
            held.remove(config.triggerKeyCode)
            autoClicker.stop()
        }
    }

    @Synchronized
    fun stopAll() {
        held.clear()
        autoClickerToggled = false
        primaryButtonDown = false
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
