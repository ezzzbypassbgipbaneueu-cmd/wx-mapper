package com.wx.mapper.domain

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import com.wx.mapper.input.WxActions
import com.wx.mapper.macros.MacroRecorder
import com.wx.mapper.model.AppConfig
import com.wx.mapper.model.DraggableItem
import com.wx.mapper.model.DraggableItemType
import com.wx.mapper.model.EventInjector
import com.wx.mapper.model.KeyMap
import com.wx.mapper.model.KeymapType
import com.wx.mapper.model.MultiModeTouchHandler
import com.wx.mapper.model.TouchMode
import com.wx.mapper.model.visualButtonScale
import com.wx.mapper.model.BUTTON_BASE_SCALE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


private const val MASK_W = 1 shl 0
private const val MASK_A = 1 shl 1
private const val MASK_S = 1 shl 2
private const val MASK_D = 1 shl 3

// Códigos "virtuais" (botões do mouse / grupo WASD). Mantidos acima dos keycodes reais do Android
// (que vão até ~330) e abaixo do tamanho dos arrays (512) para nunca colidirem com uma tecla.
// ANTES: KEYCODE_WASD = 32 era a própria tecla D, e as máscaras 1/2/4/8 e "tecla | 16" eram
// usadas como chaves do MESMO mapa das teclas reais (ex.: a tecla "1" = 8 = MASK_D, e para
// muitas teclas como A/B/C o botão "cancelar" sobrescrevia o próprio botão).
const val KEYCODE_CANCEL = 450 // legado (não usado como chave de mapa)
const val KEYCODE_WASD = 500   //pointer slot do grupo WASD
const val KEYCODE_LMC = 1 shl 6//mouse left click
const val KEYCODE_RMC = 1 shl 7//mouse right click
const val KEYCODE_MMC = 1 shl 8//mouse middle click

/** Proporção círculo/caixa medida: KeyCircular mede 66dp (50dp + padding 8dp de cada lado). */
private const val PADDED_KEY_CIRCLE_RATIO = 50f / 66f


private fun keyToWasdMask(keyCode: Int): Int = when (keyCode) {
    Key.W.nativeKeyCode -> MASK_W
    Key.A.nativeKeyCode -> MASK_A
    Key.S.nativeKeyCode -> MASK_S
    Key.D.nativeKeyCode -> MASK_D
    else -> 0
}

class EventHandler(
    private val eventInjector: EventInjector
) {

    /* ---------- state ---------- */

    // Três mapas separados (antes era um só, com chaves que colidiam):
    private val keyMap = HashMap<Int, KeyMap>(64)        // tecla física / botão do mouse
    private val cancelKeyMap = HashMap<Int, KeyMap>(16)  // posição "cancelar" de teclas cancelables
    private val wasdMap = HashMap<Int, KeyMap>(16)       // máscara de direções -> alvo do joystick

    private val pressedKeys = BooleanArray(512)
    private val mappedPressed = BooleanArray(512)
    private val cancelToggle = BooleanArray(512)
    private val pointerIds = IntArray(512) { -1 }

    private var wasdMask = 0
    private var lastWasdMask = 0

    private val keyIdMap = HashMap<Int, Int>(64)
    private var nextKeyId = 0

    /**
     * Onde a janela do overlay começa na tela. Os botões são posicionados em coordenadas da
     * janela, mas o toque injetado usa coordenadas da TELA. Se a janela estiver deslocada
     * (notch/barra), sem essa correção todo toque cai fora do lugar.
     */
    @Volatile
    var windowOrigin: Offset = Offset.Zero


    @Volatile
    private var shootingMode = false
    private val _shootingModeFlow = MutableStateFlow(false)
    val shootingModeFlow = _shootingModeFlow.asStateFlow()


    var mousePointerPosition = Offset.Zero

    /** Posição atual do cursor virtual; usada pelo autoclicker no modo "seguir ponteiro". */
    var pointerPositionProvider: (() -> Offset)? = null

    /** Grava toques (mouse esquerdo e teclas mapeadas) para virar macro. */
    val recorder = MacroRecorder()

    val wx = WxActions(eventInjector, { pointerPositionProvider?.invoke() ?: mousePointerPosition })

    var appConfig = AppConfig.Default
        set(value) {
            field = value
            wx.autoClickerConfig = value.autoClicker
            wx.setMacros(value.macros)
            eventInjector.setPerformanceMode(value.performanceMode)
        }

    private var cancelableTouchHandler: MultiModeTouchHandler = MixedModeTouchHandler(eventInjector)
    private var normalTouchHandler: MultiModeTouchHandler = MixedModeTouchHandler(eventInjector)
    private val toggleTouchHandler: MultiModeTouchHandler = ToggleModeTouchHandler(eventInjector)


    private fun keyId(keyCode: Int): Int =
        keyIdMap.getOrPut(keyCode) { nextKeyId++ }

    /* ---------- touch mode ---------- */

    fun setCancelableTouchMode(mode: TouchMode) {
        cancelableTouchHandler = when (mode) {
            TouchMode.TAP -> CancelableTapModeTouchHandler(eventInjector)
            TouchMode.HOLD -> CancelableHoldModeTouchHandler(eventInjector)
            TouchMode.MIXED -> CancelableMixedModeTouchHandler(eventInjector)
        }
    }
    fun setNormalBtnTouchMode(mode: TouchMode) {
        normalTouchHandler = when (mode) {
            TouchMode.TAP -> TapModeTouchHandler(eventInjector)
            TouchMode.HOLD -> HoldModeTouchHandler(eventInjector)
            TouchMode.MIXED -> MixedModeTouchHandler(eventInjector)
        }
    }



    private fun setShootingMode(enabled: Boolean) {
        if (shootingMode == enabled) return

        shootingMode = enabled
        _shootingModeFlow.value = enabled
    }


    /* ---------- key events ---------- */

    fun handleKeyEvent(event: KeyEvent): Boolean {
        // Autoclicker / macros têm prioridade sobre o mapeamento de toque.
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
            if (wx.onTrigger(event.keyCode, event.action == MotionEvent.ACTION_DOWN)) return true
        }
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_UP) {
            return false
        }
        if (event.keyCode !in pressedKeys.indices) return false

        // W/A/S/D só viram joystick se existir um grupo WASD no layout e a tecla não tiver
        // botão próprio. Senão são teclas comuns e podem ser mapeadas uma a uma (antes eram
        // sempre tratadas como joystick e, sem grupo, o botão mapeado em W/A/S/D nunca disparava).
        val wasdBit = if (pointerIds[KEYCODE_WASD] != -1 && !keyMap.containsKey(event.keyCode))
            keyToWasdMask(event.keyCode) else 0
        val pointerIdKey = if (wasdBit != 0) KEYCODE_WASD else event.keyCode
        val pointerId = pointerIds[pointerIdKey]
        if (pointerId == -1) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Ignore Android key-repeat events; a held key must inject only one DOWN.
                if (pressedKeys[event.keyCode] || event.repeatCount > 0) return true
                pressedKeys[event.keyCode] = true

                if (wasdBit == 0 && recorder.isRecording.value) {
                    keyMap[event.keyCode]?.let { recorder.onDown(it.tapCenter()) }
                }
                if (wasdBit != 0) {
                    wasdMask = wasdMask or wasdBit
                    handleWasd(pointerId)
                } else {
                    handleKeyDown(event.keyCode, pointerId)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                // Ignore duplicate/stale UP events. Without this guard a delayed UP
                // from a key that was never registered as pressed can release a
                // pointer that has since been reused by another input.
                if (!pressedKeys[event.keyCode]) return true
                pressedKeys[event.keyCode] = false

                if (wasdBit == 0 && recorder.isRecording.value) recorder.onUp()
                if (wasdBit != 0) {
                    wasdMask = wasdMask and wasdBit.inv()
                    handleWasd(pointerId)
                } else {
                    handleKeyUp(event.keyCode, pointerId)
                }
                return true
            }
        }
        return false
    }

    private fun KeyMap.tapCenter(): Offset =
        end?.let { Offset((position.x + it.x) / 2f, (position.y + it.y) / 2f) } ?: position

    /* ---------- WASD ---------- */

    private fun handleWasd(pointerId: Int) {
        if (wasdMask == 0) {
            eventInjector.releaseGesture(pointerId)
            lastWasdMask = 0
            return
        }

        // Resolve every combination (including 3 and 4 simultaneous directions).
        val target = wasdMap[wasdMask] ?: run {
            val directionBits = intArrayOf(MASK_W, MASK_A, MASK_S, MASK_D)
            val active = directionBits.filter { bit -> (wasdMask and bit) != 0 }
            val points = active.mapNotNull { bit -> wasdMap[bit]?.position }
            val center = active.firstNotNullOfOrNull { bit -> wasdMap[bit]?.center }
            if (points.isEmpty()) null
            else {
                val average = Offset(
                    points.sumOf { it.x.toDouble() }.toFloat() / points.size,
                    points.sumOf { it.y.toDouble() }.toFloat() / points.size
                )
                KeyMap(position = average, center = center)
            }
        }

        target?.let {
            val wasActive = lastWasdMask != 0
            if (wasActive) {
                eventInjector.transFormGesture(pointerId, it.position)
            } else {
                val center = it.center ?: return
                eventInjector.injectGesture(pointerId, center, it.position)
            }
            lastWasdMask = wasdMask
        }
    }


    /* ---------- normal keys ---------- */

    private fun handleKeyDown(keyCode: Int, pointerId: Int) {
        keyMap[keyCode]?.let {
            if (keyCode == appConfig.shootingModeKeyCode) {
                toggleShootingMode()
                return
            }
            if (it.type == KeymapType.CANCELABLE) {
                val id = keyId(keyCode)
                cancelToggle[id] = if (cancelToggle[id]){
                    cancelKeyMap[keyCode]?.let { cancelMap ->
                        cancelableTouchHandler.handleTouchEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode),cancelToggle[id],pointerId,cancelMap)
                    }?:false
                }else{
                    cancelableTouchHandler.handleTouchEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode),cancelToggle[id],pointerId,it)
                }
            } else {
                val id = keyId(keyCode)
                val handler = if (it.toggle) toggleTouchHandler else normalTouchHandler
                mappedPressed[id]=handler.handleTouchEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, keyCode),
                    mappedPressed[id],
                    pointerId,
                    it
                )
            }
        }
    }

    private fun handleKeyUp(keyCode: Int, pointerId: Int) {
        val id = keyId(keyCode)
        keyMap[keyCode]?.let {
            if (it.type == KeymapType.CANCELABLE){
                cancelToggle[id] = if (cancelToggle[id]){
                    cancelKeyMap[keyCode]?.let { cancelMap ->
                        cancelableTouchHandler.handleTouchEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode),cancelToggle[id],pointerId,cancelMap)
                    }?:false
                }else{
                    cancelableTouchHandler.handleTouchEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode),cancelToggle[id],pointerId,it)
                }
                return
            }
            val handler = if (it.toggle) toggleTouchHandler else normalTouchHandler
            mappedPressed[id] =  handler.handleTouchEvent(
                KeyEvent(KeyEvent.ACTION_UP, keyCode),
                mappedPressed[id],
                pointerId,
                it
            )
        } ?: eventInjector.releasePointer(pointerId)
    }

    /* ---------- mouse ---------- */

    fun handleMouseButton(button: Int, pressed: Boolean): Boolean {
        val keyCode = when (button) {
            MotionEvent.BUTTON_PRIMARY -> KEYCODE_LMC
            MotionEvent.BUTTON_SECONDARY -> KEYCODE_RMC
            MotionEvent.BUTTON_TERTIARY -> KEYCODE_MMC
            else -> return false
        }
        if (keyCode == KEYCODE_LMC && recorder.isRecording.value) {
            if (pressed) recorder.onDown(pointerPositionProvider?.invoke() ?: mousePointerPosition)
            else recorder.onUp()
        }
        if (keyCode == KEYCODE_LMC) wx.onPrimaryButton(pressed)
        if (wx.onTrigger(keyCode, pressed)) return true
        if (keyCode == appConfig.shootingModeKeyCode) {
            if (!pressed) return false
            toggleShootingMode()
            return true
        }
        val pointerId = pointerIds[keyCode]
        if (pointerId == -1) return false

        if (!shootingMode) {
            simulateNativeClick(pointerId = pointerId, pressed = pressed)
            return true
        }

        keyMap[keyCode]?.let {
            if (pressed)
                eventInjector.injectPointer(pointerId, it.position, it.end!!)
            else
                eventInjector.releasePointer(pointerId)
        }
        return true
    }

    fun handlePointerMove(position: Offset): Boolean {
        val pointerId = if (shootingMode)
            pointerIds[appConfig.shootingModeKeyCode]
        else
            pointerIds[KEYCODE_LMC]

        pointerId.takeIf { it != -1 }?.let {
            eventInjector.updatePointerPosition(it, position)
            return true
        }
        return false
    }

    /* ---------- shooting mode ---------- */

    private fun toggleShootingMode() {
        val newState = !shootingMode
        setShootingMode(newState)

        val key = appConfig.shootingModeKeyCode
        val pointerId = pointerIds[key]

        if (pointerId != -1) {
            if (newState)
                keyMap[key]?.let { eventInjector.injectPointer(pointerId, it.position) }
            else
                eventInjector.releasePointer(pointerId)
        }
    }


    private fun simulateNativeClick(
        pointerId: Int,
        position: Offset = mousePointerPosition,
        pressed: Boolean
    ) {
        if (pressed)
            eventInjector.injectPointer(pointerId, position)
        else
            eventInjector.releasePointer(pointerId)
    }

    /* ---------- mapping ---------- */

    /**
     * Área de toque de um botão: pequena região no CENTRO do círculo que o usuário vê.
     * [topLeft] em coordenadas da janela; [boxSize] é a caixa medida (px, sem escala);
     * [circleRatio] é quanto dessa caixa é o círculo desenhado.
     */
    private fun tapArea(topLeft: Offset, boxSize: Int, circleRatio: Float): Pair<Offset, Offset> {
        val box = boxSize.toFloat()
        val center = topLeft + windowOrigin + Offset(box / 2f, box / 2f)
        val diameter = box * circleRatio * appConfig.visualButtonScale()
        val half = (diameter * 0.2f).coerceAtLeast(1f)   // 40% do diâmetro, no centro
        return Offset(center.x - half, center.y - half) to Offset(center.x + half, center.y + half)
    }

    fun updateKeyMapping(items: List<DraggableItem>) {
        keyMap.clear()
        cancelKeyMap.clear()
        wasdMap.clear()
        pointerIds.fill(-1)
        pressedKeys.fill(false)
        mappedPressed.fill(false)
        cancelToggle.fill(false)
        keyIdMap.clear()
        nextKeyId = 0
        wasdMask = 0
        lastWasdMask = 0
        var nextPointer = 0

        fun alloc(key: Int) {
            if (key in pointerIds.indices && pointerIds[key] == -1)
                pointerIds[key] = nextPointer++
        }

        items.forEach { item ->
            when (item) {
                is DraggableItem.WASDGroup -> {
                    // Mesma fonte de coordenadas das outras teclas (posição + origem da janela +
                    // tamanho medido): é o caminho que comprovadamente acerta o lugar.
                    // O escalonamento é feito em torno do centro, então o centro não muda.
                    var center = item.center
                    var w = item.w
                    var a = item.a
                    var s = item.s
                    var d = item.d
                    if (item.size > 0) {
                        val box = item.size.toFloat()
                        val scale = item.scale.coerceIn(0.5f, 2f) * BUTTON_BASE_SCALE
                        val cx = item.position.x + windowOrigin.x + box / 2f
                        val cy = item.position.y + windowOrigin.y + box / 2f
                        val r = box * scale / 2f
                        center = Offset(cx, cy)
                        w = Offset(cx, cy - r)
                        a = Offset(cx - r, cy)
                        s = Offset(cx, cy + r)
                        d = Offset(cx + r, cy)
                    }
                    wasdMap[MASK_W] = KeyMap(position = w, center = center)
                    wasdMap[MASK_A] = KeyMap(position = a, center = center)
                    wasdMap[MASK_S] = KeyMap(position = s, center = center)
                    wasdMap[MASK_D] = KeyMap(position = d, center = center)
                    val wa = Offset(
                        x = w.x - ((w.x - a.x) / 2),
                        y = w.y + ((a.y - w.y) / 2)
                    )
                    val wd = Offset(
                        x = w.x + ((d.x - w.x) / 2),
                        y = w.y + ((d.y - w.y) / 2)
                    )
                    val sa = Offset(
                        x = a.x + ((s.x - a.x) / 2),
                        y = a.y + ((s.y - a.y) / 2)
                    )
                    val sd = Offset(
                        x = s.x + ((d.x - s.x) / 2),
                        y = s.y + ((d.y - s.y) / 2)
                    )
                    wasdMap[MASK_W or MASK_A] = KeyMap(position = wa, center = center)
                    wasdMap[MASK_W or MASK_D] = KeyMap(position = wd, center = center)
                    wasdMap[MASK_S or MASK_A] = KeyMap(position = sa, center = center)
                    wasdMap[MASK_S or MASK_D] = KeyMap(position = sd, center = center)

                    alloc(KEYCODE_WASD)
                }

                is DraggableItem.VariableKey -> {
                    val k = item.keyCode ?: return@forEach
                    val (start, end) = tapArea(item.position, item.size, PADDED_KEY_CIRCLE_RATIO)
                    keyMap[k] = KeyMap(position = start, end = end, toggle = item.toggle)
                    alloc(k)
                }

                is DraggableItem.FixedKey -> {
                    val k = when (item.type) {
                        DraggableItemType.SHOOTING_MODE -> appConfig.shootingModeKeyCode
                        DraggableItemType.FIRE -> appConfig.fireKeyCode
                        DraggableItemType.SCOPE -> appConfig.scopeKeyCode
                        else -> item.keyCode
                    }
                    val (start, end) = tapArea(item.position, item.size, 1f)
                    keyMap[k] = KeyMap(position = start, end = end)
                    alloc(k)
                }

                is DraggableItem.CancelableKey -> {
                    val k = item.keyCode ?: return@forEach
                    val (start, end) = tapArea(item.position, item.size, 1f)
                    val (cStart, cEnd) = tapArea(item.cancelPosition, item.size, 1f)
                    keyMap[k] = KeyMap(type = KeymapType.CANCELABLE, position = start, end = end)
                    cancelKeyMap[k] = KeyMap(type = KeymapType.CANCELABLE, position = cStart, end = cEnd)
                    alloc(k)
                }
            }
        }

        alloc(KEYCODE_LMC)
    }

    fun clear() {
        wx.stopAll()
        eventInjector.clear()
        pressedKeys.fill(false)
        mappedPressed.fill(false)
        cancelToggle.fill(false)
        wasdMask = 0
        shootingMode = false
        _shootingModeFlow.value = false
        lastWasdMask = 0
    }
}
