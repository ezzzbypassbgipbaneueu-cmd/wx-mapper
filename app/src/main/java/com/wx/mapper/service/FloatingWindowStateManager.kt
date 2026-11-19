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
import com.wx.mapper.model.GameProfile
import com.wx.mapper.domain.EventHandler
import com.wx.mapper.input.WxRuntime
import com.wx.mapper.macros.Macro
import com.wx.mapper.macros.MacroTriggers
import com.wx.mapper.util.keyCodeToString
import android.widget.Toast
import com.wx.mapper.service.TypingState
import com.wx.mapper.data.external.ShizukuSystemServerAPi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
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

    private var lastScreenW = 0
    private var lastScreenH = 0

    val pointerSensitivity = MutableStateFlow(0.5f)
    val overlayOpacity = MutableStateFlow(0.5f)

    private var buttonConfigKey :Preferences.Key<String>? = null

    // Configuração GLOBAL (teclas, cursor, tema...) e perfil do jogo ativo (autoclicker, macros...).
    // _appConfig é a junção dos dois: é ela que o resto do app lê.
    private val _globalConfig = MutableStateFlow(AppConfig.Default)
    private val _profile = MutableStateFlow(GameProfile())
    private val _activePackage = MutableStateFlow<String?>(null)
    /** Pacote do jogo cujo perfil/keymap está carregado agora (null = nenhum ainda). */
    val activePackage = _activePackage.asStateFlow()
    private var switchJob: Job? = null

    private val _appConfig = MutableStateFlow(AppConfig.Default)
    val keysConfig = _appConfig.asStateFlow()

    private fun buildEffectiveConfig(): AppConfig {
        val global = _globalConfig.value
        if (_activePackage.value == null) return global
        val p = _profile.value
        return global.copy(autoClicker = p.autoClicker.sanitized(), macros = p.macros)
    }

    /** Recalcula a config efetiva; só reaplica no EventHandler se algo além do cursor mudou. */
    private fun applyEffectiveConfig(force: Boolean = false) {
        val previous = _appConfig.value
        val next = buildEffectiveConfig()
        _appConfig.value = next
        TypingState.lightMode.value = next.performanceMode
        val previousWithoutCursor = previous.copy(
            cursorStyle = next.cursorStyle,
            cursorSizeDp = next.cursorSizeDp,
            cursorColor = next.cursorColor,
            cursorOpacity = next.cursorOpacity,
            cursorImagePath = next.cursorImagePath,
            cursorImageVersion = next.cursorImageVersion,
            cursorImageHotspotCenter = next.cursorImageHotspotCenter
        )
        if (force || previousWithoutCursor != next) {
            eventHandler.appConfig = next
        }
    }
    /** true enquanto uma macro está sendo gravada (mostra o aviso GRAVANDO no overlay). */
    val macroRecording get() = eventHandler.recorder.isRecording


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

    // Declarado ANTES do init: o init já inicia o observador que usa isso.
    private val autoFreeze = AutoFreezeManager(context, scope)

    init {
        lastScreenW = screenWidthPx
        lastScreenH = screenHeightPx
        eventHandler.pointerPositionProvider = { pointerOffset.value }
        WxRuntime.attach(eventHandler.wx)
        scope.launch {
            isBubbleExpanded.drop(1).collect { isExpanded ->
                if (isExpanded) return@collect
                // Fechou o editor: salva o keymap e as opções DESTE jogo.
                persistActiveGame()
                _globalConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
                applyEffectiveConfig(force = true)
            }
        }
        scope.launch {
            TypingState.editableFocused.collect { focused ->
                if (!focused) autoTypingSuppressed.value = false
            }
        }
        scope.launch {
            // Mudanças globais feitas nas telas (cursor, teclas...) valem na hora.
            dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).drop(1).collect {
                _globalConfig.value = it
                applyEffectiveConfig()
            }
        }
        scope.launch {
            _globalConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
            eventHandler.setCancelableTouchMode(_globalConfig.value.cancellableTouchMode)
            eventHandler.setNormalBtnTouchMode(_globalConfig.value.normalBtnTouchMode)
            applyEffectiveConfig(force = true)
        }
        observeActiveGame()
        observeForegroundGame()
    }

    /** Acompanha o perfil e o keymap salvos do jogo ativo (editados pelas telas do app). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeActiveGame() {
        scope.launch {
            _activePackage
                .flatMapLatest { pkg ->
                    if (pkg == null) emptyFlow<GameProfile?>() else dataStoreManager.getProfile(pkg)
                }
                .collect { saved ->
                    if (saved == null || saved == _profile.value) return@collect
                    _profile.value = saved
                    if (!_isBubbleExpanded.value) {
                        pointerSensitivity.value = saved.pointerSensitivity
                        overlayOpacity.value = saved.overlayOpacity
                    }
                    applyEffectiveConfig()
                }
        }
        scope.launch {
            _activePackage
                .flatMapLatest { pkg ->
                    if (pkg == null) emptyFlow<List<DraggableItem>>()
                    else dataStoreManager.getButtons(DataStoreManager.getButtonsConfigKey(pkg)).distinctUntilChanged()
                }
                .collect { items ->
                    // Keymap alterado por fora (ex.: copiado de outro jogo). Não mexe durante a edição.
                    if (!_isBubbleExpanded.value && items != _containerItems.value) {
                        _containerItems.value = items
                        eventHandler.updateKeyMapping(items)
                    }
                }
        }
    }

    /** Se você abrir outro jogo cadastrado (por qualquer caminho), troca para o perfil dele. */
    private fun observeForegroundGame() {
        scope.launch {
            TypingState.foregroundPackage.collect { pkg ->
                if (pkg == null) return@collect
                val games = dataStoreManager.getList(DataStoreManager.ADDED_PACKAGES).first()
                // Congelar/descongelar apps automaticamente conforme o jogo entra/sai da frente.
                autoFreeze.onForeground(pkg, pkg in games)
                if (pkg == _activePackage.value || _isBubbleExpanded.value) return@collect
                if (pkg in games) switchGame(pkg, announce = true)
            }
        }
    }

    /** Salva o keymap e as opções do jogo ativo no armazenamento. */
    private suspend fun persistActiveGame() {
        val pkg = _activePackage.value ?: return
        dataStoreManager.save(DataStoreManager.getButtonsConfigKey(pkg), containerItems.value)
        val updated = _profile.value.copy(
            pointerSensitivity = pointerSensitivity.value,
            overlayOpacity = overlayOpacity.value
        )
        _profile.value = updated
        dataStoreManager.saveProfile(pkg, updated)
    }

    fun labelFor(packageName: String): String = gameLabel(packageName)

    private fun gameLabel(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    /** Carrega o keymap + perfil do jogo. Chamado ao abrir o jogo pelo app ou ao detectar a troca. */
    fun loadButtonsConfig(packageName: String) {
        autoFreeze.onForeground(packageName, true)
        switchGame(packageName, announce = false)
    }

    fun switchGame(packageName: String, announce: Boolean = false) {
        if (_activePackage.value == packageName) return
        switchJob?.cancel()
        switchJob = scope.launch {
            // Primeiro salva o jogo que estava ativo, para nunca perder o keymap dele.
            persistActiveGame()
            eventHandler.clear()
            val profile = dataStoreManager.ensureProfile(packageName)
            val key = DataStoreManager.getButtonsConfigKey(packageName)
            val items = dataStoreManager.getButtons(key).first()
            // Daqui até o fim não há pausa: a troca acontece inteira, sem estado pela metade.
            buttonConfigKey = key
            _profile.value = profile
            _containerItems.value = items
            pointerSensitivity.value = profile.pointerSensitivity
            overlayOpacity.value = profile.overlayOpacity
            _activePackage.value = packageName
            applyEffectiveConfig(force = true)
            eventHandler.updateKeyMapping(items)
            if (announce) {
                Toast.makeText(context, "Perfil: ${gameLabel(packageName)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * A tela mudou (rotação etc.): mantém o cursor na mesma posição relativa e dentro dos
     * limites novos. Sem isso ele podia ficar fora da tela até o próximo movimento.
     */
    fun onScreenSizeChanged() {
        val w = screenWidthPx
        val h = screenHeightPx
        if (w <= 0 || h <= 0) return
        val old = _mousePointerOffset.value
        val ow = if (lastScreenW > 0) lastScreenW else w
        val oh = if (lastScreenH > 0) lastScreenH else h
        lastScreenW = w
        lastScreenH = h
        val moved = Offset(
            (old.x * w / ow).coerceIn(0f, (w - 1).coerceAtLeast(0).toFloat()),
            (old.y * h / oh).coerceIn(0f, (h - 1).coerceAtLeast(0).toFloat())
        )
        _mousePointerOffset.value = moved
        eventHandler.mousePointerPosition = moved
    }

    /** Reaplica o mapeamento depois que o overlay já foi composto e os tamanhos reais foram medidos. */
    fun refreshKeyMapping() {
        if (!_isBubbleExpanded.value) eventHandler.updateKeyMapping(containerItems.value)
    }

    fun addNewItem(itemType: DraggableItemType) {
        val itemID = containerItems.value.sumOf { it.id } + 1
        val offset = Offset(0f,200f)
        val item = when (itemType) {
            DraggableItemType.KEY -> {
                DraggableItem.VariableKey(itemID, position = offset, size = 0)
            }

            DraggableItemType.TOGGLE_KEY -> {
                DraggableItem.VariableKey(itemID, position = offset, size = 0, toggle = true)
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

    /**
     * Atalho: adiciona W, A, S e D como 4 teclas comuns, já ligadas, em cruz. Cada uma vira um
     * ponto da tela; é só arrastar cada botão para o lugar certo no jogo.
     */
    fun addWasdKeys() {
        var nextId = (containerItems.value.maxOfOrNull { it.id } ?: 0) + 1
        val base = Offset(200f, 400f)
        val step = 130f
        val layout = listOf(
            KeyEvent.KEYCODE_W to Offset(base.x, base.y - step),
            KeyEvent.KEYCODE_A to Offset(base.x - step, base.y),
            KeyEvent.KEYCODE_S to Offset(base.x, base.y),
            KeyEvent.KEYCODE_D to Offset(base.x + step, base.y),
        )
        val used = containerItems.value.mapNotNull { (it as? DraggableItem.VariableKey)?.keyCode }.toSet()
        val added = layout.filter { it.first !in used }.map { (code, pos) ->
            DraggableItem.VariableKey(nextId++, position = pos, keyCode = code, size = 0)
        }
        if (added.isEmpty()) {
            Toast.makeText(context, "W, A, S e D já estão no keymap.", Toast.LENGTH_SHORT).show()
            return
        }
        _containerItems.value = containerItems.value + added
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
        if (keyEvent.keyCode == config.macroRecordKeyCode) {
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) toggleMacroRecording()
            return true
        }
        if (keyEvent.keyCode == config.typingModeKeyCode) {
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) setManualTyping(true)
            return true
        }
        return eventHandler.handleKeyEvent(keyEvent)
    }

    /** Começa a gravar uma macro; ao chamar de novo, para e salva a gravação na lista de macros. */
    fun toggleMacroRecording() {
        val recorder = eventHandler.recorder
        if (!recorder.isRecording.value) {
            recorder.start()
            Toast.makeText(context, "Gravando macro… aperte a mesma tecla para parar.", Toast.LENGTH_SHORT).show()
            return
        }
        val recorded = recorder.stop()
        val taps = recorded.count { it is com.wx.mapper.macros.MacroAction.Tap }
        if (taps == 0) {
            Toast.makeText(context, "Nada gravado.", Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = _activePackage.value
        if (pkg == null) {
            Toast.makeText(context, "Abra o jogo pelo WX MAPPER para salvar a macro no perfil dele.", Toast.LENGTH_LONG).show()
            return
        }
        val current = _appConfig.value
        val profile = _profile.value
        val id = (profile.macros.maxOfOrNull { it.id } ?: 0) + 1
        val used = buildSet {
            profile.macros.forEach { m -> m.triggerKeyCode?.let { add(it) } }
            add(current.macroRecordKeyCode)
            add(current.typingModeKeyCode)
            add(current.recordingModeKeyCode)
            add(current.shootingModeKeyCode)
        }
        val trigger = MacroTriggers.nextFree(used)
        val updated = profile.copy(
            macros = profile.macros + Macro(id = id, name = "Gravação $id", triggerKeyCode = trigger, actions = recorded)
        )
        _profile.value = updated
        applyEffectiveConfig(force = true)
        scope.launch { dataStoreManager.saveProfile(pkg, updated) }
        val how = trigger?.let { "Aperte ${it.keyCodeToString()} para rodar (e de novo para parar)." }
            ?: "Defina uma tecla na tela de Macros."
        Toast.makeText(context, "Macro salva ($taps toques). $how", Toast.LENGTH_LONG).show()
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
                // O Android junta (batch) vários movimentos do mouse em um único evento quando o
                // FPS está baixo: o evento traz só o último delta e o resto vem em "histórico".
                // Somamos tudo; antes só o último era usado e o cursor ficava lento com FPS baixo.
                var relX = motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                var relY = motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                for (h in 0 until motionEvent.historySize) {
                    relX += motionEvent.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, h)
                    relY += motionEvent.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, h)
                }
                val delta = Offset(relX, relY) * sensitivityNow

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

    /** Passa para a próxima imagem salva na galeria de cursores. */
    fun cycleCursorImage() {
        val images = com.wx.mapper.util.CursorImages.list(context)
        if (images.isEmpty()) return
        val current = _appConfig.value.cursorImagePath
        val index = images.indexOfFirst { it.absolutePath == current }
        val next = images[(index + 1) % images.size]
        updateCursorSettings {
            copy(
                cursorImagePath = next.absolutePath,
                cursorImageVersion = System.currentTimeMillis(),
                cursorStyle = CursorStyle.IMAGE
            )
        }
    }

    /** Jogos cadastrados (para a lista de perfis da bolinha). */
    fun addedGames(): kotlinx.coroutines.flow.Flow<List<String>> =
        dataStoreManager.getList(DataStoreManager.ADDED_PACKAGES)

    private var profileSaveJob: Job? = null

    /** Muda o autoclicker do jogo ativo sem sair do jogo; salva no perfil. */
    fun updateAutoClicker(
        transform: com.wx.mapper.autoclicker.AutoClickerConfig.() -> com.wx.mapper.autoclicker.AutoClickerConfig
    ) {
        val pkg = _activePackage.value ?: return
        val updated = _profile.value.copy(autoClicker = _profile.value.autoClicker.transform().sanitized())
        _profile.value = updated
        applyEffectiveConfig()
        // Salva só quando parar de mexer no slider (evita o valor "voltar" durante o arraste).
        profileSaveJob?.cancel()
        profileSaveJob = scope.launch {
            kotlinx.coroutines.delay(250)
            dataStoreManager.saveProfile(pkg, updated)
        }
    }

    /** Galeria de PNGs de cursor salvos. */
    fun cursorImages(): List<java.io.File> = com.wx.mapper.util.CursorImages.list(context)

    fun selectCursorImage(file: java.io.File) = updateCursorSettings {
        copy(
            cursorImagePath = file.absolutePath,
            cursorImageVersion = System.currentTimeMillis(),
            cursorStyle = CursorStyle.IMAGE
        )
    }

    fun updateCursorHotspot(center: Boolean) = updateCursorSettings { copy(cursorImageHotspotCenter = center) }

    fun updatePerformanceMode(enabled: Boolean) = updateCursorSettings { copy(performanceMode = enabled) }

    fun updateAutoTyping(enabled: Boolean) = updateCursorSettings { copy(autoTypingMode = enabled) }

    fun updateCursorStyle(style: CursorStyle) = updateCursorSettings { copy(cursorStyle = style) }

    fun updateCursorSize(sizeDp: Float) =
        updateCursorSettings { copy(cursorSizeDp = sizeDp.coerceIn(12f, 64f)) }

    fun updateCursorColor(color: Int) = updateCursorSettings { copy(cursorColor = color) }

    fun updateCursorOpacity(opacity: Float) =
        updateCursorSettings { copy(cursorOpacity = opacity.coerceIn(0.1f, 1f)) }

    private fun updateCursorSettings(transform: AppConfig.() -> AppConfig) {
        // Só mexe na parte GLOBAL: o autoclicker/macros do jogo ativo não vão parar aqui.
        val updated = _globalConfig.value.transform()
        _globalConfig.value = updated
        applyEffectiveConfig()
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
        autoFreeze.releaseNow()
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