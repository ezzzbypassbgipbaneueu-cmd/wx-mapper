package com.wx.mapper.input

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.wx.mapper.autoclicker.AutoClicker
import com.wx.mapper.autoclicker.WxHighPriorityDispatcher
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + WxHighPriorityDispatcher),
) {
    val autoClicker = AutoClicker(scope, injector)
    val macroPlayer = MacroPlayer(scope, injector)

    @Volatile
    var autoClickerConfig: AutoClickerConfig = AutoClickerConfig()
        set(value) {
            val old = field
            field = value.sanitized()
            if (!field.enabled || old.triggerKeyCode != field.triggerKeyCode ||
                old.toggleMode != field.toggleMode
            ) {
                held.clear()
                autoToggledOn = false
                autoClicker.stop()
            }
        }

    @Volatile
    private var macrosByKey: Map<Int, Macro> = emptyMap()

    private val held = HashSet<Int>()
    private var primaryButtonDown = false

    /** Modo alternar: o autoclicker está "ligado" (mesmo que pausado enquanto o botão esquerdo está preso). */
    private var autoToggledOn = false

    fun setMacros(macros: List<Macro>) {
        macrosByKey = macros.filter { it.triggerKeyCode != null }
            .associateBy { it.triggerKeyCode!! }
    }

    /**
     * O autoclicker funciona enquanto o gatilho estiver pressionado.
     * Ao receber ACTION_UP, para imediatamente e solta o toque virtual.
     */
    @Synchronized
    fun onTrigger(keyCode: Int, pressed: Boolean): Boolean {
        val config = autoClickerConfig
        val isAuto = config.enabled && keyCode == config.triggerKeyCode
        val macro = macrosByKey[keyCode]
        if (!isAuto && macro == null) return false

        val transition = if (pressed) held.add(keyCode) else held.remove(keyCode)

        if (isAuto) {
            if (config.toggleMode) {
                // ALTERNAR: só a primeira batida do botão conta (repetição e soltar são ignorados).
                if (pressed && transition) {
                    if (autoToggledOn) {
                        autoToggledOn = false
                        autoClicker.stop()
                    } else {
                        autoToggledOn = true
                        if (!primaryButtonDown) {
                            autoClicker.start(config) { clickPosition(config) }
                        }
                    }
                }
            } else if (pressed) {
                if (transition && !primaryButtonDown) {
                    autoClicker.start(config) { clickPosition(config) }
                }
            } else if (transition) {
                autoClicker.stop()
            }
        }

        if (macro != null && pressed && transition) macroPlayer.toggle(macro)
        return true
    }

    /**
     * O botão esquerdo pausa temporariamente o autoclicker para permitir
     * arrastar/mover a câmera. Ao soltá-lo, retoma apenas se o gatilho
     * configurado ainda estiver pressionado.
     */
    @Synchronized
    fun onPrimaryButton(pressed: Boolean) {
        if (primaryButtonDown == pressed) return
        primaryButtonDown = pressed
        val config = autoClickerConfig
        if (!config.enabled) return

        if (pressed) {
            if (autoClicker.isRunning.value) autoClicker.stop()
        } else if (if (config.toggleMode) autoToggledOn else held.contains(config.triggerKeyCode)) {
            autoClicker.start(config) { clickPosition(config) }
        }
    }

    /** Reconcilia os botões físicos e recupera ACTION_UP que tenha sido perdido. */
    @Synchronized
    fun syncMouseButtons(buttonState: Int) {
        val primaryNow = buttonState and MotionEvent.BUTTON_PRIMARY != 0
        onPrimaryButton(primaryNow)

        val config = autoClickerConfig
        // No modo alternar o estado "ligado" não depende do botão estar preso.
        if (config.toggleMode) return
        val triggerMask = when (config.triggerKeyCode) {
            KEYCODE_LMC -> MotionEvent.BUTTON_PRIMARY
            KEYCODE_RMC -> MotionEvent.BUTTON_SECONDARY
            KEYCODE_MMC -> MotionEvent.BUTTON_TERTIARY
            else -> return
        }

        if (buttonState and triggerMask == 0) {
            val wasHeld = held.remove(config.triggerKeyCode)
            if (wasHeld) autoClicker.stop()
        } else if (!held.contains(config.triggerKeyCode)) {
            held.add(config.triggerKeyCode)
            if (!primaryButtonDown) {
                autoClicker.start(config) { clickPosition(config) }
            }
        }
    }

    @Synchronized
    fun stopAll() {
        held.clear()
        autoToggledOn = false
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
