package com.wx.mapper.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.View
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import com.wx.mapper.util.keyCodeToString
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.wx.mapper.MainActivity
import com.wx.mapper.R
import com.wx.mapper.model.DraggableItemType
import com.wx.mapper.ui.overlay.CursorOverlay
import com.wx.mapper.ui.overlay.FloatingBubble
import com.wx.mapper.ui.overlay.ItemsContainer
import com.wx.mapper.ui.overlay.MenuItems
import com.wx.mapper.ui.overlay.ServiceLifecycleOwner
import com.wx.mapper.ui.overlay.SettingsLayout
import com.wx.mapper.ui.overlay.CursorGlyph
import com.wx.mapper.ui.overlay.CursorPanel
import com.wx.mapper.ui.overlay.GameEntry
import com.wx.mapper.ui.overlay.ProfilePanel
import com.wx.mapper.ui.overlay.RailButton
import com.wx.mapper.model.CursorStyle
import com.wx.mapper.ui.theme.WxTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import com.wx.mapper.model.RecordingHideMode
import com.wx.mapper.util.ScreenshotExclusion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt


@AndroidEntryPoint
class FloatingBubbleService : Service() {
    private val TAG = "FloatingBubbleService"
    private lateinit var floatingBubbleLP: WindowManager.LayoutParams

    @Inject
    lateinit var stateManager: Lazy<FloatingWindowStateManager>

    companion object {
        val INTENT_EXTRA_PACAKAGE = "launchedPackageName"
        const val ACTION_TOGGLE_RECORDING = "com.wx.mapper.action.TOGGLE_RECORDING"
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
    }

    private val lifecycleOwner = ServiceLifecycleOwner()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var containerView: ComposeView? = null
    /** Raiz que de fato é adicionada ao WindowManager; recebe TODA tecla, com ou sem foco interno. */
    private var containerRoot: KeyCaptureFrameLayout? = null
    private lateinit var itemsContainerLP: WindowManager.LayoutParams
    /** true enquanto o modo gravação está ligado com FLAG_SECURE nas janelas do overlay. */
    private var secureActive = false
    /** true enquanto o modo gravação está ligado com SKIP_SCREENSHOT. */
    private var skipActive = false
    private var skipApplied = false
    /** SKIP_SCREENSHOT não funcionou: o overlay cai no modo "só botões". */
    private val skipFailed = MutableStateFlow(false)

    private fun applySkip(skip: Boolean): Boolean {
        var ok = true
        containerRoot?.let { ok = ScreenshotExclusion.setSkip(it, skip) && ok }
        floatingBubbleView?.let { ok = ScreenshotExclusion.setSkip(it, skip) && ok }
        return ok
    }
    private var floatingBubbleView: ComposeView? = null



    // ---- Captura do ponteiro (cursor custom) ------------------------------------------------
    // O Android SOLTA a captura do ponteiro sozinho quando a janela perde o foco, é recriada ou
    // redimensionada (girar a tela, notificação, teclado, trocar de app...). Sem a captura, o
    // mouse volta a ser o cursor padrão do sistema e o cursor custom para de mexer.
    // Aqui a captura é pedida de novo sempre que isso acontece.
    private var captureAttempts = 0
    private val captureRunnable = Runnable { tryAcquirePointerCapture() }
    private var displayListenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) onScreenChanged()
        }
    }

    private fun shouldHoldCapture(): Boolean {
        val sm = stateManager.get()
        return containerRoot != null && !sm.isBubbleExpanded.value && !sm.isTypingMode.value
    }

    /** Agenda (com debounce) uma nova tentativa de captura. */
    private fun scheduleCapture(delayMs: Long = 0L, resetAttempts: Boolean = true) {
        val v = containerView ?: return
        if (resetAttempts) captureAttempts = 0
        v.removeCallbacks(captureRunnable)
        v.postDelayed(captureRunnable, delayMs)
    }

    private fun tryAcquirePointerCapture() {
        val v = containerView ?: return
        if (!shouldHoldCapture()) return
        if (!v.isAttachedToWindow) {
            if (captureAttempts++ < 20) scheduleCapture(200, resetAttempts = false)
            return
        }
        if (!v.hasFocus()) v.requestFocus()
        if (!v.hasPointerCapture()) v.requestPointerCapture()
        // Se a janela ainda não tem foco (rotação em andamento), tenta de novo em instantes.
        if (!v.hasPointerCapture() && captureAttempts++ < 20) {
            scheduleCapture(250, resetAttempts = false)
        }
    }

    /** Rotação / mudança de tela: refaz tamanho da janela, posição do cursor e captura. */
    private fun onScreenChanged() {
        val root = containerRoot ?: return
        root.removeCallbacks(screenChangedRunnable)
        // Espera a rotação terminar de aplicar antes de medir.
        root.postDelayed(screenChangedRunnable, 200)
    }

    private val screenChangedRunnable = Runnable {
        val root = containerRoot ?: return@Runnable
        val sm = stateManager.get()
        sm.onScreenSizeChanged()
        try {
            sm.windowManager.updateViewLayout(root, itemsContainerLP)
        } catch (_: Exception) {
        }
        root.post { sm.refreshKeyMapping() }
        scheduleCapture(100)
    }

    /**
     * Recria a superfície do overlay: reaplica os flags da janela e força um novo desenho.
     * É o mesmo efeito de abrir e fechar a bolinha, só que automático quando o sistema pode ter
     * descartado o buffer do overlay (pouca memória, tela ligando).
     */
    private fun refreshOverlay() {
        val root = containerRoot ?: return
        try {
            val sm = stateManager.get()
            applyOverlayMode(sm.isBubbleExpanded.value, sm.isTypingMode.value)
            root.invalidate()
            containerView?.invalidate()
            floatingBubbleView?.invalidate()
        } catch (_: Exception) {
        }
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            containerRoot?.postDelayed({ refreshOverlay() }, 400)
        }
    }
    private var screenReceiverRegistered = false

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // O sistema pode soltar os buffers do overlay quando falta memória; redesenha depois.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            containerRoot?.postDelayed({ refreshOverlay() }, 600)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onScreenChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_RECORDING) {
            // Ação da notificação: esconde/mostra tudo sem recriar o overlay.
            if (containerRoot != null) stateManager.get().toggleRecordingMode()
            return START_NOT_STICKY
        }
        _isRunning.value = true
        if (!screenReceiverRegistered) {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        val channel = NotificationChannel(
            "channel1",
            "WX MAPPER Overlay Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val launchedPackageName = intent?.getStringExtra(INTENT_EXTRA_PACAKAGE)
        launchedPackageName?.let {
            stateManager.get().loadButtonsConfig(it)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_TOGGLE_RECORDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, "channel1").apply {
            setSmallIcon(R.drawable.ic_launcher_monochrome)
            addAction(0, "Esconder / mostrar tudo (gravação)", toggleIntent)
            setContentTitle(ContextCompat.getString(baseContext, R.string.app_name))
            setContentText(
                "${
                    ContextCompat.getString(
                        baseContext,
                        R.string.app_name
                    )
                } Overlay service is running"
            )
        }.build()
        startForeground(1, notification)

        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleOwner.performRestore(null)
        }
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        init()
        return START_NOT_STICKY
    }

    private fun applyCutoutAndInsets(lp: WindowManager.LayoutParams) {
        // Sem isso o sistema empurra a janela para fora do notch / barras (principalmente em
        // paisagem). A janela ficava deslocada: o cursor desenhado não coincidia com o toque
        // injetado e a faixa da direita ficava inalcançável.
        lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.setFitInsetsTypes(0)
        }
    }

    private fun init() {
        if (containerRoot != null) return // já inicializado

        floatingBubbleLP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        floatingBubbleLP.gravity = Gravity.START or Gravity.TOP
        applyCutoutAndInsets(floatingBubbleLP)

        itemsContainerLP = WindowManager.LayoutParams()
        itemsContainerLP.copyFrom(floatingBubbleLP)
        itemsContainerLP.gravity = Gravity.START or Gravity.TOP
        itemsContainerLP.x = 0
        itemsContainerLP.y = 0
        itemsContainerLP.flags =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        itemsContainerLP.width = WindowManager.LayoutParams.MATCH_PARENT
        itemsContainerLP.height = WindowManager.LayoutParams.MATCH_PARENT
        applyCutoutAndInsets(itemsContainerLP)

        containerView =
            getItemsContainerView { id ->
                stateManager.get().removeItem(id)
            }

        containerRoot = KeyCaptureFrameLayout(this).apply {
            keyInterceptor = { event ->
                val sm = stateManager.get()
                if (sm.isBubbleExpanded.value || sm.isTypingMode.value) false
                else sm.onKeyEvent(event)
            }
            addView(
                containerView!!,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            // Perdeu a captura sem a gente pedir (rotação, foco...): pede de novo.
            onPointerCaptureLost = {
                if (shouldHoldCapture()) scheduleCapture(50)
            }
            // A janela voltou a ter foco: é quando a captura pode ser retomada.
            onWindowFocusGained = {
                if (shouldHoldCapture()) scheduleCapture(0)
            }
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            // Mede onde a janela realmente está na tela (origem) e corrige cursor/botões.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                stateManager.get().onOverlayWindowPositioned(loc[0], loc[1])
                if (skipActive) ScreenshotExclusion.setSkip(v, true)
                // Janela redimensionada (rotação etc.): garante a captura do ponteiro.
                if (shouldHoldCapture() && !(containerView?.hasPointerCapture() ?: true)) scheduleCapture(100)
            }
        }

        floatingBubbleView = getFloatingBubbleView(
            onCLick = {
                val sm = stateManager.get()
                if (sm.isTypingMode.value && !sm.isBubbleExpanded.value) sm.exitTypingMode()
                else sm.onFloatingBubbleClick()
            },
            onLongClick = {
                val sm = stateManager.get()
                if (!sm.isBubbleExpanded.value) {
                    if (sm.isTypingMode.value) sm.exitTypingMode() else sm.setManualTyping(true)
                }
            },
            onAddItem = { itemType ->
                Log.i(TAG, "init: $itemType")
                stateManager.get().addNewItem(itemType)
            }
        )
        scope.launch {
            combine(
                stateManager.get().isBubbleExpanded,
                stateManager.get().isTypingMode
            ) { expanded, typing -> expanded to typing }
                .collect { (expanded, typing) -> applyOverlayMode(expanded, typing) }
        }
        // Reaplica SKIP_SCREENSHOT se o sistema recriar a camada (relayout) durante a gravação.
        floatingBubbleView?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (skipActive) ScreenshotExclusion.setSkip(v, true)
        }
        scope.launch {
            // Modo gravação (config "recordingHideMode"):
            //  - BUTTONS_ONLY: bolha e botões somem; o cursor continua visível.
            //  - SKIP_SCREENSHOT: tudo visível para você; a camada do overlay é excluída da captura
            //    (API oculta; se falhar, cai em BUTTONS_ONLY).
            //  - FLAG_SECURE: tudo visível; janelas do overlay seguras (pode gravar preto).
            combine(
                stateManager.get().recordingMode,
                stateManager.get().keysConfig.map { it.recordingHideMode }.distinctUntilChanged()
            ) { recording, mode -> recording to mode }
                .collect { (recording, mode) ->
                    secureActive = recording && mode == RecordingHideMode.FLAG_SECURE
                    skipActive = recording && mode == RecordingHideMode.SKIP_SCREENSHOT
                    var hide = recording && mode == RecordingHideMode.BUTTONS_ONLY
                    if (skipActive) {
                        // Pequeno atraso: a camada precisa existir/estar desenhada.
                        kotlinx.coroutines.delay(50)
                        val ok = applySkip(true)
                        skipApplied = ok
                        skipFailed.value = !ok
                        if (!ok) {
                            hide = true
                            android.widget.Toast.makeText(
                                this@FloatingBubbleService,
                                "SKIP_SCREENSHOT indisponível neste aparelho: escondendo só os botões.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        skipFailed.value = false
                        if (skipApplied) { applySkip(false); skipApplied = false }
                    }
                    val wm = stateManager.get().windowManager
                    floatingBubbleView?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
                    floatingBubbleView?.let { v ->
                        if (v.isAttachedToWindow) {
                            var f = floatingBubbleLP.flags
                            f = if (hide) f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            else f and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                            f = if (secureActive) f or WindowManager.LayoutParams.FLAG_SECURE
                            else f and WindowManager.LayoutParams.FLAG_SECURE.inv()
                            floatingBubbleLP.flags = f
                            wm.updateViewLayout(v, floatingBubbleLP)
                        }
                    }
                    containerRoot?.let { r ->
                        if (r.isAttachedToWindow) {
                            itemsContainerLP.flags = if (secureActive)
                                itemsContainerLP.flags or WindowManager.LayoutParams.FLAG_SECURE
                            else
                                itemsContainerLP.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                            wm.updateViewLayout(r, itemsContainerLP)
                        }
                    }
                }
        }
        stateManager.get().windowManager.addView(containerRoot, itemsContainerLP)
        floatingBubbleLP.y = 200
        stateManager.get().windowManager.addView(floatingBubbleView, floatingBubbleLP)
        if (!displayListenerRegistered) {
            getSystemService(DisplayManager::class.java)
                .registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
            displayListenerRegistered = true
        }
        containerView?.postDelayed({
            val sm = stateManager.get()
            sm.refreshKeyMapping()
            if (!sm.isBubbleExpanded.value && !sm.isTypingMode.value) {
                containerView?.requestFocus()
                containerView?.requestPointerCapture()
            }
        }, 1000)
    }

    /** Aplica o estado do overlay: editando / mapeando / digitando. */
    private fun applyOverlayMode(expanded: Boolean, typing: Boolean) {
        val root = containerRoot ?: return
        val wm = stateManager.get().windowManager
        itemsContainerLP.width = WindowManager.LayoutParams.MATCH_PARENT
        itemsContainerLP.height = WindowManager.LayoutParams.MATCH_PARENT
        // FLAG_SECURE só nas janelas do overlay (nunca na Activity do app) e só no modo gravação.
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (secureActive) WindowManager.LayoutParams.FLAG_SECURE else 0)
        when {
            expanded -> {
                itemsContainerLP.flags = base
                wm.updateViewLayout(root, itemsContainerLP)
                containerView?.clearFocus()
                containerView?.requestFocus()
                containerView?.releasePointerCapture()
                stateManager.get().clearActivePointers()
            }
            typing -> {
                // MODO DIGITAR: o overlay não pega foco nem toque; o teclado e o mouse
                // vão direto para o app/jogo, então o campo de texto recebe tudo sem clicar em nada.
                itemsContainerLP.flags = base or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                // Cancela qualquer recaptura agendada antes de largar o foco.
                containerView?.removeCallbacks(captureRunnable)
                containerView?.releasePointerCapture()
                stateManager.get().clearActivePointers()
                wm.updateViewLayout(root, itemsContainerLP)
            }
            else -> {
                itemsContainerLP.flags = base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(root, itemsContainerLP)
                containerView?.post {
                    // Se virou modo digitar nesse meio tempo, NÃO pega foco nem captura.
                    if (!shouldHoldCapture()) return@post
                    containerView?.clearFocus()
                    containerView?.requestFocus()
                    containerView?.requestPointerCapture()
                    scheduleCapture(150)
                }
            }
        }
    }


    private fun getFloatingBubbleView(
        onCLick: () -> Unit,
        onLongClick: () -> Unit,
        onAddItem: (DraggableItemType) -> Unit
    ): ComposeView {
        val composeView = ComposeView(this)
        composeView.setContent {
            FloatingBubbleLayout(
                onAddItem = onAddItem,
                onBubbleClick = onCLick,
                onBubbleLongClick = onLongClick
            )
        }
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        return composeView
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun getItemsContainerView(
        onRemove: (Int) -> Unit
    ): ComposeView {
        val composeView = ComposeView(this)
        // As teclas físicas são interceptadas na raiz (KeyCaptureFrameLayout), que recebe
        // todo KeyEvent da janela independentemente de qual view interna está com foco.
        composeView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        composeView.setContent {
            WxTheme {
                val draggableItems by stateManager.get().containerItems.collectAsState()
                val itemsContainerOpacity by stateManager.get().overlayOpacity.collectAsState()
                val isBubbleExpanded by stateManager.get().isBubbleExpanded.collectAsState()
                val isShootingMode by stateManager.get().isShootingMode.collectAsState()
                // State (sem delegate `by`): ler `.value` só no desenho evita recompor a cada movimento do mouse.
                val pointerState = stateManager.get().pointerOffset.collectAsState()
                val keysConfig by stateManager.get().keysConfig.collectAsState()
                val windowOrigin by stateManager.get().windowOrigin.collectAsState()
                val isTyping by stateManager.get().isTypingMode.collectAsState()
                val isRecording by stateManager.get().recordingMode.collectAsState()
                val skipFailedState by skipFailed.collectAsState()
                // true = botões/cursor continuam desenhados no modo gravação (escondidos pelo sistema).
                val keepHud = keysConfig.recordingHideMode != RecordingHideMode.BUTTONS_ONLY && !skipFailedState
                val macroRecording by stateManager.get().macroRecording.collectAsState()
                Box {
                    if (macroRecording && (!isRecording || keepHud)) {
                        androidx.compose.material3.Text(
                            "● GRAVANDO MACRO — aperte ${keysConfig.macroRecordKeyCode.keyCodeToString()} para parar",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                            modifier = Modifier
                                .padding(8.dp)
                                .background(
                                    androidx.compose.ui.graphics.Color(0xCCD32F2F),
                                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    // Opacidade 0: esconde botões E cursor virtual.
                    if (!isBubbleExpanded && itemsContainerOpacity == 0f)
                        return@Box
                    // Cursor: continua visível no modo gravação (senão você fica sem ver onde clica).
                    if (!isBubbleExpanded && !isShootingMode && !isTyping) {
                        CursorOverlay(
                            position = { pointerState.value },
                            style = keysConfig.cursorStyle,
                            sizeDp = keysConfig.cursorSizeDp,
                            color = keysConfig.cursorColor,
                            opacity = keysConfig.cursorOpacity,
                            imagePath = keysConfig.cursorImagePath,
                            imageVersion = keysConfig.cursorImageVersion,
                            imageHotspotCenter = keysConfig.cursorImageHotspotCenter,
                            windowOrigin = windowOrigin
                        )
                    }
                    // Modo gravação "só botões": os botões somem. Nos outros modos o sistema os omite da gravação.
                    if (!isBubbleExpanded && isRecording && !keepHud)
                        return@Box
                    ItemsContainer(
                        // ModulateAlpha: cada botão é desenhado já com a opacidade, SEM criar uma camada
                        // (buffer) de tela cheia. Essa camada era a suspeita da "tela escura".
                        Modifier.graphicsLayer {
                            alpha = if (isBubbleExpanded) 1f else itemsContainerOpacity
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        },
                        appConfig = keysConfig,
                        containerItems = draggableItems,
                        editingEnabled = isBubbleExpanded,
                        onRemove = onRemove,
                    )
                }
            }
        }
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        composeView.isFocusable = true
        composeView.isClickable = true
        composeView.isFocusableInTouchMode = true
        composeView.post {
            composeView.setOnCapturedPointerListener { _, motionEvent ->
                stateManager.get().onMouseEvent(motionEvent)
            }
        }
        composeView.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) {
                // Ignore transient focus changes caused by pointer capture / overlay
                // updates. Clear mapped inputs only if the overlay is still unfocused
                // on the next UI-loop turn.
                view.post {
                    if (!stateManager.get().isBubbleExpanded.value &&
                        !stateManager.get().isTypingMode.value && !view.hasFocus()) {
                        stateManager.get().onFocusLost()
                        view.requestFocus()
                        view.requestPointerCapture()
                        scheduleCapture(150)
                    }
                }
            }
        }
        return composeView
    }


    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        _isRunning.value = false
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            screenReceiverRegistered = false
        }
        removeAllViews()
        stateManager.get().onDestroy()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        super.onDestroy()
    }

    private fun removeAllViews() {
        if (displayListenerRegistered) {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        containerView?.removeCallbacks(captureRunnable)
        containerRoot?.removeCallbacks(screenChangedRunnable)
        containerRoot?.let {
            stateManager.get().windowManager.removeViewImmediate(it)
        }
        containerRoot = null
        floatingBubbleView?.let {
            stateManager.get().windowManager.removeViewImmediate(it)
        }
    }

    @Composable
    private fun FloatingBubbleLayout(
        modifier: Modifier = Modifier,
        onAddItem: (DraggableItemType) -> Unit,
        onBubbleClick: () -> Unit,
        onBubbleLongClick: () -> Unit = {}
    ) {
        val isTypingNow by stateManager.get().isTypingMode.collectAsState()
        var opacity by remember { mutableFloatStateOf(0.5f) }
        val isExpanded by stateManager.get().isBubbleExpanded.collectAsState()
        var itemsMenuVisible by remember { mutableStateOf(false) }
        var settingsLayoutVisible by remember { mutableStateOf(false) }
        var profileVisible by remember { mutableStateOf(false) }
        var cursorVisible by remember { mutableStateOf(false) }
        LaunchedEffect(isExpanded) {
            opacity = if (isExpanded) 1f else 0.5f
            itemsMenuVisible = isExpanded && itemsMenuVisible
            settingsLayoutVisible = isExpanded && settingsLayoutVisible
            profileVisible = isExpanded && profileVisible
            cursorVisible = isExpanded && cursorVisible
        }
        Box(
            modifier = modifier
                .alpha(opacity)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            opacity = 1.0f
                        }, onDragEnd = {
                            if (!isExpanded) opacity = 0.5f
                            if (floatingBubbleLP.x < stateManager.get().screenWidthPx / 2) {
                                floatingBubbleLP.x = 0
                                stateManager.get().windowManager.updateViewLayout(
                                    floatingBubbleView,
                                    floatingBubbleLP
                                )
                            } else {
                                floatingBubbleLP.x =
                                    stateManager.get().screenWidthPx - (floatingBubbleView?.width
                                        ?: 0)
                                stateManager.get().windowManager.updateViewLayout(
                                    floatingBubbleView,
                                    floatingBubbleLP
                                )
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        floatingBubbleLP.x += dragAmount.x.roundToInt()
                        floatingBubbleLP.y += dragAmount.y.roundToInt()
                        stateManager.get().windowManager.updateViewLayout(
                            floatingBubbleView,
                            floatingBubbleLP
                        )
                    }
                }
        ) {
            ConstraintLayout {
                val (background, bubbleLayout, otherLayout, bubble) = createRefs()
                Box(
                    modifier = Modifier
                        .constrainAs(background) {
                            start.linkTo(bubble.start)
                            top.linkTo(bubble.top)
                            end.linkTo(bubble.end)
                            bottom.linkTo(bubbleLayout.bottom)
                            height = Dimension.fillToConstraints
                            width = Dimension.fillToConstraints
                        }
                        .background(color = MaterialTheme.colorScheme.surface, CircleShape)
                )
                FloatingBubble(
                    modifier = Modifier
                        .size(50.dp)
                        .constrainAs(bubble) { },
                    expanded = isExpanded, typing = isTypingNow,
                    onClick = onBubbleClick, onLongClick = onBubbleLongClick
                )
                AnimatedVisibility(
                    visible = isExpanded,
                    Modifier.constrainAs(bubbleLayout) {
                        top.linkTo(bubble.bottom)
                        end.linkTo(bubble.end)
                    }) {
                    Column(
                        modifier = Modifier.padding(bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        RailButton(selected = itemsMenuVisible, onClick = {
                            val next = !itemsMenuVisible
                            itemsMenuVisible = next
                            settingsLayoutVisible = false
                            profileVisible = false
                            cursorVisible = false
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Adicionar item",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        RailButton(selected = profileVisible, onClick = {
                            val next = !profileVisible
                            profileVisible = next
                            itemsMenuVisible = false
                            settingsLayoutVisible = false
                            cursorVisible = false
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = "Perfil do jogo",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        RailButton(selected = cursorVisible, onClick = {
                            val next = !cursorVisible
                            cursorVisible = next
                            itemsMenuVisible = false
                            settingsLayoutVisible = false
                            profileVisible = false
                        }) {
                            CursorGlyph(color = MaterialTheme.colorScheme.onSurface)
                        }
                        RailButton(selected = settingsLayoutVisible, onClick = {
                            val next = !settingsLayoutVisible
                            settingsLayoutVisible = next
                            itemsMenuVisible = false
                            profileVisible = false
                            cursorVisible = false
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Ajustes",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = (itemsMenuVisible),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .constrainAs(otherLayout) {
                            start.linkTo(bubble.end)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        }) {
                    val activeGame by stateManager.get().activePackage.collectAsState()
                    MenuItems(
                        gameName = activeGame?.let { stateManager.get().labelFor(it) } ?: "",
                        onAddWasdKeys = {
                            stateManager.get().addWasdKeys()
                            itemsMenuVisible = false
                        },
                        onItemClick = {
                            onAddItem(it)
                            itemsMenuVisible = false
                        }
                    )
                }
                AnimatedVisibility(
                    visible = profileVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .constrainAs(otherLayout) {
                            start.linkTo(bubble.end)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        }) {
                    val sm = stateManager.get()
                    val activeGame by sm.activePackage.collectAsState()
                    val keysConfig by sm.keysConfig.collectAsState()
                    val sensitivity by sm.pointerSensitivity.collectAsState()
                    val gamesFlow = remember { sm.addedGames() }
                    val packages by gamesFlow.collectAsState(initial = emptyList())
                    val games = remember(packages) { packages.map { GameEntry(it, sm.labelFor(it)) } }
                    ProfilePanel(
                        games = games,
                        activePackage = activeGame,
                        autoClicker = keysConfig.autoClicker,
                        pointerSensitivity = sensitivity,
                        onSelectGame = { sm.switchGame(it, announce = true) },
                        onAutoClickerChange = { updated -> sm.updateAutoClicker { updated } },
                        onPointerSensChange = { sm.pointerSensitivity.value = it },
                        onManageProfiles = {
                            val intent = Intent(baseContext, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            }
                            baseContext.startActivity(intent)
                            sm.onFloatingBubbleClick()
                        }
                    )
                }
                AnimatedVisibility(
                    visible = cursorVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .constrainAs(otherLayout) {
                            start.linkTo(bubble.end)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        }) {
                    val sm = stateManager.get()
                    val keysConfig by sm.keysConfig.collectAsState()
                    val images = remember { sm.cursorImages() }
                    CursorPanel(
                        style = keysConfig.cursorStyle,
                        sizeDp = keysConfig.cursorSizeDp,
                        color = keysConfig.cursorColor,
                        opacity = keysConfig.cursorOpacity,
                        images = images,
                        selectedImagePath = keysConfig.cursorImagePath,
                        hotspotCenter = keysConfig.cursorImageHotspotCenter,
                        onStyleChange = { style ->
                            if (style == CursorStyle.IMAGE && keysConfig.cursorImagePath == null) {
                                sm.cycleCursorImage()
                            } else {
                                sm.updateCursorStyle(style)
                            }
                        },
                        onSizeChange = { sm.updateCursorSize(it) },
                        onColorChange = { sm.updateCursorColor(it) },
                        onOpacityChange = { sm.updateCursorOpacity(it) },
                        onSelectImage = { sm.selectCursorImage(it) },
                        onHotspotChange = { sm.updateCursorHotspot(it) },
                        onAddImage = {
                            val intent = Intent(baseContext, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                action = MainActivity.INTENT_ACTION_SETTINGS
                            }
                            baseContext.startActivity(intent)
                            sm.onFloatingBubbleClick()
                        }
                    )
                }
                AnimatedVisibility(
                    visible = (settingsLayoutVisible),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .constrainAs(otherLayout) {
                            start.linkTo(bubble.end)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                            end.linkTo(parent.end)
                            width = Dimension.preferredWrapContent
                        }) {
                    val pointerSensitivity by stateManager.get().pointerSensitivity.collectAsState()
                    val overlayOpacity by stateManager.get().overlayOpacity.collectAsState()
                    val keysConfig by stateManager.get().keysConfig.collectAsState()
                    SettingsLayout(
                        pointerSensitivity = pointerSensitivity,
                        overlayOpacity = overlayOpacity,
                        onOpacityChange = { stateManager.get().overlayOpacity.value = it },
                        onPointerSensChange = { stateManager.get().pointerSensitivity.value = it },
                        cursorStyle = keysConfig.cursorStyle,
                        cursorSizeDp = keysConfig.cursorSizeDp,
                        cursorColor = keysConfig.cursorColor,
                        cursorOpacity = keysConfig.cursorOpacity,
                        onCursorStyleChange = { stateManager.get().updateCursorStyle(it) },
                        onCursorSizeChange = { stateManager.get().updateCursorSize(it) },
                        onCursorColorChange = { stateManager.get().updateCursorColor(it) },
                        onCursorOpacityChange = { stateManager.get().updateCursorOpacity(it) },
                        cursorHasImage = keysConfig.cursorImagePath != null,
                        onNextCursorImage = { stateManager.get().cycleCursorImage() },
                        performanceMode = keysConfig.performanceMode,
                        onPerformanceModeChange = { stateManager.get().updatePerformanceMode(it) },
                        onOpenProcessManager = {
                            val intent = Intent(baseContext, com.wx.mapper.ui.ProcessManagerActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            baseContext.startActivity(intent)
                            stateManager.get().onFloatingBubbleClick()
                        },
                        autoTypingMode = keysConfig.autoTypingMode,
                        onAutoTypingChange = { stateManager.get().updateAutoTyping(it) },
                        onTypingModeClick = {
                            stateManager.get().onFloatingBubbleClick()
                            stateManager.get().setManualTyping(true)
                        },
                        onRecordingModeClick = {
                            stateManager.get().onFloatingBubbleClick()
                            stateManager.get().toggleRecordingMode()
                        },
                        onAdvancedSettingsClick = {
                            val intent = Intent(baseContext, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                action = MainActivity.INTENT_ACTION_SETTINGS
                            }
                            baseContext.startActivity(intent)
                            stateManager.get().onFloatingBubbleClick()
                        },
                        onCloseOverlayClick = {
                            stopSelf()
                        })
                }
            }
        }
    }
}


@Preview
@Composable
fun PreviewFloatingBubble() {
    Box(
        modifier = Modifier
    ) {
        ConstraintLayout {
            val (background, bubbleLayout, otherLayout, bubble) = createRefs()
            Box(
                modifier = Modifier
                    .constrainAs(background) {
                        start.linkTo(bubble.start)
                        top.linkTo(bubble.top)
                        end.linkTo(bubble.end)
                        bottom.linkTo(bubbleLayout.bottom)
                        height = Dimension.fillToConstraints
                        width = Dimension.fillToConstraints
                    }
                    .background(color = MaterialTheme.colorScheme.surface, CircleShape)
            )
            FloatingBubble(
                modifier = Modifier
                    .size(50.dp)
                    .constrainAs(bubble) { },
                expanded = true, onClick = { })
            AnimatedVisibility(
                visible = true,
                Modifier.constrainAs(bubbleLayout) {
                    top.linkTo(bubble.bottom)
                    end.linkTo(bubble.end)
                }) {
                Column {
                    IconButton(onClick = {
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add Item"
                        )
                    }

                    IconButton(onClick = {
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = (false),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .constrainAs(otherLayout) {
                        start.linkTo(bubble.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    }) {
                MenuItems(onItemClick = { })
            }
            AnimatedVisibility(
                visible = (true),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .constrainAs(otherLayout) {
                        start.linkTo(bubble.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        end.linkTo(parent.end)
                        width = Dimension.preferredWrapContent
                    }) {
                SettingsLayout(
                    onAdvancedSettingsClick = {},
                    onPointerSensChange = {},
                    onOpacityChange = { },
                    onCloseOverlayClick = {}
                )
            }
        }
    }
}

/** Raiz do overlay: intercepta toda tecla antes de qualquer view filha (não depende de foco interno). */
class KeyCaptureFrameLayout(context: Context) : FrameLayout(context) {
    var keyInterceptor: ((KeyEvent) -> Boolean)? = null
    var onPointerCaptureLost: (() -> Unit)? = null
    var onWindowFocusGained: (() -> Unit)? = null

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        if (!hasCapture) onPointerCaptureLost?.invoke()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) onWindowFocusGained?.invoke()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}
