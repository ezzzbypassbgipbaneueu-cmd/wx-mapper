package com.wx.mapper.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.Preferences
import com.wx.mapper.data.local.DataStoreManager
import com.wx.mapper.model.AppConfig
import com.wx.mapper.model.CursorStyle
import com.wx.mapper.model.DraggableItem
import com.wx.mapper.model.DraggableItemType
import com.wx.mapper.domain.EventHandler
import com.wx.mapper.input.WxRuntime
import com.wx.mapper.service.TypingState
import com.wx.mapper.data.external.ShizukuSystemServerAPi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

class FloatingWindowStateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreManager: DataStoreManager
) {

    val scope = CoroutineScope(Dispatchers.Main +SupervisorJob())

    private val shizukuSystemServerAPi = ShizukuSystemServerAPi()
    private val eventHandler: EventHandler by lazy { shizukuSystemServerAPi.getEventHandler() }
    val windowManager: WindowManager by lazy { context.getSystemService(WindowManager::class.java) }
    private val displayMetrics = context.resources.displayMetrics

    // Tamanho REAL da tela (rotação atual). É o espaço de coordenadas do toque injetado.
    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(m)
        return m
    }
    val screenWidthPx: Int
        get() = realMetrics().widthPixels.takeIf { it > 0 } ?: displayMetrics.widthPixels
    val screenHeightPx: Int
        get() = realMetrics().heightPixels.takeIf { it > 0 } ?: displayMetrics.heightPixels

    /**
     * Canto superior esquerdo da janela do overlay NA TELA. Se o sistema desloca a janela
     * (notch, barra de navegação), o cursor desenhado e os botões ficam deslocados em relação
     * ao toque injetado: era o "clique um pouco mais pra esquerda" e a "parede" na direita.
     */
    private val _windowOrigin = MutableStateFlow(Offset.Zero)
    val windowOrigin = _windowOrigin.asStateFlow()

    fun onOverlayWindowPositioned(x: Int, y: Int) {
        val origin = Offset(x.toFloat(), y.toFloat())
        if (origin == _windowOrigin.value) return
        _windowOrigin.value = origin
        eventHandler.windowOrigin = origin
        // reaplica o mapeamento com a origem nova
        if (!_isBubbleExpanded.value) eventHandler.updateKeyMapping(containerItems.value)
    }

    val pointerSensitivity = MutableStateFlow(0.5f)
    val overlayOpacity = MutableStateFlow(0.5f)

    private var buttonConfigKey :Preferences.Key<String>? = null

    private val _appConfig =MutableStateFlow(AppConfig.Default)
    val keysConfig = _appConfig.asStateFlow()


    private val _isBubbleExpanded = MutableStateFlow(false)
    val isBubbleExpanded = _isBubbleExpanded.asStateFlow()

    private val _mousePointerOffset =
        MutableStateFlow(Offset(screenWidthPx / 2f, screenHeightPx / 2f))
    val pointerOffset = _mousePointerOffset.asStateFlow()

    /** Modo gravação: esconde TUDO do keymapper (botões, cursor e bolha). O mapeamento continua. */
    val recordingMode = MutableStateFlow(false)

    // Modo digitar: o overlay larga o foco do teclado para o app/jogo de baixo.
    private val manualTyping = MutableStateFlow(false)
    private val autoTypingSuppressed = MutableStateFlow(false)
    val isTypingMode by lazy {
        combine(
            manualTyping,
            TypingState.editableFocused,
            autoTypingSuppressed,
            _appConfig
        ) { manual, editable, suppressed, config ->
            manual || (config.autoTypingMode && editable && !suppressed)
        }.stateIn(scope, SharingStarted.Eagerly, false)
    }

    private val _containerItems = MutableStateFlow(listOf<DraggableItem>())
    val containerItems = _containerItems.asStateFlow()

    val isShootingMode = eventHandler.shootingModeFlow

    init {
        eventHandler.pointerPositionProvider = { pointerOffset.value }
        WxRuntime.attach(eventHandler.wx)
        scope.launch {
            isBubbleExpanded.drop(1).collect{isExpanded->
                if (isExpanded)
                    return@collect
                buttonConfigKey?.let {
                    dataStoreManager.save(DataStoreManager.OVERLAY_OPACITY,overlayOpacity.value)
                    dataStoreManager.save(DataStoreManager.POINTER_SENSITIVITY,pointerSensitivity.value)
                    dataStoreManager.save(it,containerItems.value)
                }
                _appConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
                eventHandler.appConfig = _appConfig.value
            }
        }
        scope.launch {
            TypingState.editableFocused.collect { focused ->
                if (!focused) autoTypingSuppressed.value = false
            }
        }
        scope.launch {
            // Mudanças feitas nas telas (autoclicker/macros) valem na hora, sem reiniciar o overlay.
            dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).drop(1).collect {
                val previous = _appConfig.value
                _appConfig.value = it
                // Cursor-only changes do not need to rebuild macro/autoclicker configuration.
                val previousWithoutCursor = previous.copy(
                    cursorStyle = it.cursorStyle,
                    cursorSizeDp = it.cursorSizeDp,
                    cursorColor = it.cursorColor,
                    cursorOpacity = it.cursorOpacity,
                    cursorImagePath = it.cursorImagePath,
                    cursorImageVersion = it.cursorImageVersion,
                    cursorImageHotspotCenter = it.cursorImageHotspotCenter
                )
                if (previousWithoutCursor != it) {
                    eventHandler.appConfig = it
                }
            }
        }
        scope.launch {
            _appConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
            eventHandler.setCancelableTouchMode(_appConfig.value.cancellableTouchMode)
            eventHandler.setNormalBtnTouchMode(_appConfig.value.normalBtnTouchMode)
            eventHandler.appConfig = _appConfig.value
        }

    }

    fun loadButtonsConfig(packageName:String){
        buttonConfigKey= DataStoreManager.getButtonsConfigKey(packageName)
        scope.launch {
            _containerItems.value=dataStoreManager.getButtons(buttonConfigKey!!).first()
            eventHandler.updateKeyMapping(containerItems.value)
            dataStoreManager.getFloat(DataStoreManager.OVERLAY_OPACITY).first()?.let {
                overlayOpacity.value = it
            }
            dataStoreManager.getFloat(DataStoreManager.POINTER_SENSITIVITY).first()?.let {
                pointerSensitivity.value = it
            }
        }
    }

    fun addNewItem(itemType: DraggableItemType) {
        val itemID = containerItems.value.sumOf { it.id } + 1
        val offset = Offset(0f,200f)
        val item = when (itemType) {
            DraggableItemType.KEY -> {
                DraggableItem.VariableKey(itemID, position = offset, size = 0)
            }

            DraggableItemType.WASD_KEY -> {
                DraggableItem.WASDGroup(itemID, offset)
            }

            DraggableItemType.SHOOTING_MODE -> {
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.shootingModeKeyCode
                )

            }

            DraggableItemType.FIRE -> {
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.fireKeyCode
                )
            }
            DraggableItemType.BAG_MAP->{
                DraggableItem.CancelableKey(
                    itemID,
                    offset,
                    cancelPosition = offset,
                    size = 0,
                    type = itemType
                )}
            DraggableItemType.SCOPE->{
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.scopeKeyCode
                    )
            }
        }
        _containerItems.value = containerItems.value.plus(item)
    }

    fun removeItem(id: Int) {
        _containerItems.value = _containerItems.value.filterNot { it.id == id }
    }

    fun onFloatingBubbleClick() {
        _isBubbleExpanded.value = !_isBubbleExpanded.value
        if (!_isBubbleExpanded.value) {
            eventHandler.updateKeyMapping(containerItems.value)
        }
    }

    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (_isBubbleExpanded.value)
            return false
        if (isTypingMode.value)
            return false
        val config = _appConfig.value
        if (keyEvent.keyCode == config.recordingModeKeyCode) {
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) toggleRecordingMode()
            return true
        }
        if (keyEvent.keyCode == config.typingModeKeyCode) {
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) setManualTyping(true)
            return true
        }
        return eventHandler.handleKeyEvent(keyEvent)
    }

    fun toggleRecordingMode() {
        recordingMode.value = !recordingMode.value
        eventHandler.clear()
    }

    fun setManualTyping(enabled: Boolean) {
        manualTyping.value = enabled
        eventHandler.clear()
    }

    /** Toque na bolha enquanto digita: volta ao mapeamento. */
    fun exitTypingMode() {
        manualTyping.value = false
        if (TypingState.editableFocused.value) autoTypingSuppressed.value = true
        eventHandler.clear()
    }

    fun onMouseEvent(motionEvent: MotionEvent): Boolean {
        if (isBubbleExpanded.value || isTypingMode.value)
            return false
        eventHandler.wx.syncMouseButtons(motionEvent.buttonState)
        when (motionEvent.action) {

            MotionEvent.ACTION_BUTTON_PRESS -> {
                eventHandler.mousePointerPosition = pointerOffset.value
                eventHandler.handleMouseButton(motionEvent.actionButton, true)
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                eventHandler.handleMouseButton(motionEvent.actionButton, false)
            }

            MotionEvent.ACTION_MOVE -> {
                // Pointer capture delivers relative deltas. rawX/rawY are absolute screen
                // coordinates and must never be accumulated as movement.
                val sensitivityNow = 10f * pointerSensitivity.value
                val delta = Offset(
                    motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                    motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                ) * sensitivityNow

                if (!isShootingMode.value) {
                    val oldPosition = _mousePointerOffset.value
                    val newPosition = Offset(
                        (oldPosition.x + delta.x).coerceIn(
                            0f,
                            (screenWidthPx - 1).coerceAtLeast(0).toFloat()
                        ),
                        (oldPosition.y + delta.y).coerceIn(
                            0f,
                            (screenHeightPx - 1).coerceAtLeast(0).toFloat()
                        )
                    )
                    _mousePointerOffset.value = newPosition
                    eventHandler.mousePointerPosition = newPosition

                    // Inject only the movement that was actually applied after edge clamping.
                    val appliedDelta = newPosition - oldPosition
                    return eventHandler.handlePointerMove(appliedDelta)
                }

                return eventHandler.handlePointerMove(delta)
            }
        }
        return true
    }

    fun updateAutoTyping(enabled: Boolean) = updateCursorSettings { copy(autoTypingMode = enabled) }

    fun updateCursorStyle(style: CursorStyle) = updateCursorSettings { copy(cursorStyle = style) }

    fun updateCursorSize(sizeDp: Float) =
        updateCursorSettings { copy(cursorSizeDp = sizeDp.coerceIn(12f, 64f)) }

    fun updateCursorColor(color: Int) = updateCursorSettings { copy(cursorColor = color) }

    fun updateCursorOpacity(opacity: Float) =
        updateCursorSettings { copy(cursorOpacity = opacity.coerceIn(0.1f, 1f)) }

    private fun updateCursorSettings(transform: AppConfig.() -> AppConfig) {
        val updated = _appConfig.value.transform()
        _appConfig.value = updated
        scope.launch {
            dataStoreManager.save(DataStoreManager.KEYS_CONFIG, updated)
        }
    }

    fun clearActivePointers() {
        eventHandler.clear()
    }

    /** Perdeu o foco do overlay: nenhum "soltar" chegará, então para autoclicker/macros. */
    fun onFocusLost() {
        // A focus change can prevent the matching physical key-up from arriving.
        // Release all virtual pointers so a game key cannot remain stuck.
        eventHandler.clear()
    }

    fun onDestroy(){
        _isBubbleExpanded.value = false
        eventHandler.clear()
        WxRuntime.detach(eventHandler.wx)
        eventHandler.wx.release()
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun getStatusBrHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        } else {
            val resourceId =
                Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android")
            return if (resourceId > 0) Resources.getSystem()
                .getDimensionPixelSize(resourceId) else 0

        }
    }
}