package com.wx.mapper.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
import com.wx.mapper.ui.theme.WxTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private var floatingBubbleView: ComposeView? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_RECORDING) {
            // Ação da notificação: esconde/mostra tudo sem recriar o overlay.
            if (containerRoot != null) stateManager.get().toggleRecordingMode()
            return START_NOT_STICKY
        }
        _isRunning.value = true
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
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            // Mede onde a janela realmente está na tela (origem) e corrige cursor/botões.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                stateManager.get().onOverlayWindowPositioned(loc[0], loc[1])
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
        scope.launch {
            // Modo gravação. Dois jeitos (config "recordingSecureWindow"):
            //  - normal: a bolha e os botões somem; o cursor continua visível para você.
            //  - seguro (experimental): tudo continua visível para você, mas as janelas do overlay
            //    recebem FLAG_SECURE para o sistema omiti-las da gravação.
            combine(
                stateManager.get().recordingMode,
                stateManager.get().keysConfig.map { it.recordingSecureWindow }.distinctUntilChanged()
            ) { recording, secure -> recording to secure }
                .collect { (recording, secure) ->
                    val hide = recording && !secure
                    secureActive = recording && secure
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
                containerView?.releasePointerCapture()
                stateManager.get().clearActivePointers()
                wm.updateViewLayout(root, itemsContainerLP)
            }
            else -> {
                itemsContainerLP.flags = base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(root, itemsContainerLP)
                containerView?.post {
                    containerView?.clearFocus()
                    containerView?.requestFocus()
                    containerView?.requestPointerCapture()
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
                val pointerOffset by stateManager.get().pointerOffset.collectAsState()
                val keysConfig by stateManager.get().keysConfig.collectAsState()
                val windowOrigin by stateManager.get().windowOrigin.collectAsState()
                val isTyping by stateManager.get().isTypingMode.collectAsState()
                val isRecording by stateManager.get().recordingMode.collectAsState()
                val macroRecording by stateManager.get().macroRecording.collectAsState()
                Box {
                    if (macroRecording && (!isRecording || keysConfig.recordingSecureWindow)) {
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
                            position = pointerOffset,
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
                    // Modo gravação normal: só os botões somem. No modo seguro eles ficam (FLAG_SECURE).
                    if (!isBubbleExpanded && isRecording && !keysConfig.recordingSecureWindow)
                        return@Box
                    ItemsContainer(
                        Modifier.alpha(if (isBubbleExpanded) 1f else itemsContainerOpacity),
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
        removeAllViews()
        stateManager.get().onDestroy()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        super.onDestroy()
    }

    private fun removeAllViews() {
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
        LaunchedEffect(isExpanded) {
            opacity = if (isExpanded) 1f else 0.5f
            itemsMenuVisible = isExpanded && itemsMenuVisible
            settingsLayoutVisible = isExpanded && settingsLayoutVisible
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
                    Column {
                        IconButton(onClick = {
                            itemsMenuVisible = !itemsMenuVisible
                            settingsLayoutVisible = false
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Add Item"
                            )
                        }

                        IconButton(onClick = {
                            settingsLayoutVisible = !settingsLayoutVisible
                            itemsMenuVisible = false
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
                    MenuItems(onItemClick = {
                        onAddItem(it)
                        itemsMenuVisible = false
                    })
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}
