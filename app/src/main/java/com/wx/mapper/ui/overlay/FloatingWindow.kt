package com.wx.mapper.ui.overlay

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import com.wx.mapper.model.BUTTON_BASE_SCALE
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.wx.mapper.R
import com.wx.mapper.model.AppConfig
import com.wx.mapper.model.CursorStyle
import com.wx.mapper.model.DraggableItem
import com.wx.mapper.model.DraggableItemType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingBubble(
    expanded: Boolean = false,
    typing: Boolean = false,
    modifier: Modifier = Modifier.size(50.dp),
    onLongClick: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    Column(modifier = modifier
        .clip(CircleShape)
        .combinedClickable(onClick = { onClick() }, onLongClick = { onLongClick() })) {
        if (expanded) {
            Image(
                imageVector = Icons.Rounded.Done,
                contentDescription = "",
                modifier = modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(8.dp)
            )
        } else if (typing) {
            // Modo digitar ativo: toque para voltar ao mapeamento
            Image(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "Typing mode",
                modifier = modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                    .padding(12.dp)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.logo_raw),
                contentDescription = "",
                modifier = modifier
                    .clip(CircleShape)
                    .background(Color.Black)
                    .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            )
        }
    }
}

@SuppressLint("UnrememberedMutableState")
@Composable
fun ItemsContainer(
    modifier: Modifier = Modifier,
    containerItems: List<DraggableItem>,
    appConfig: AppConfig = AppConfig.Default,
    editingEnabled: Boolean = true,
    onRemove: (Int) -> Unit
) {
    Box(
        modifier
            .fillMaxSize()
    ) {
        containerItems.forEach { item ->
            Log.i("ItemsContainerCompose", "ItemsContainer: added item: $item")

            var position by remember(item.id) { mutableStateOf(item.position) }
            // Escala do usuário (Settings / arrastar no WASD) x tamanho base reduzido.
            // O valor salvo no WASD guarda só a escala do usuário (antes guardava também o
            // fator base e encolhia a cada vez que o overlay era reaberto).
            var userScale by remember(item.id) {
                mutableFloatStateOf(
                    if (item is DraggableItem.WASDGroup) item.scale.coerceIn(0.5f, 2f) else 1f
                )
            }
            val scale = (if (item is DraggableItem.WASDGroup) userScale
            else appConfig.buttonScale.coerceIn(0.35f, 2f)) * BUTTON_BASE_SCALE
            if (item is DraggableItem.CancelableKey) {
                if (item.type == DraggableItemType.BAG_MAP) {
                    var cancelableKeyPosition by remember(item.id) { mutableStateOf(item.cancelPosition) }
                    var hasFocus by remember { mutableStateOf(false) }
                    val keyFocusRequester = remember { FocusRequester() }
                    var keyCode by remember(item.id) { mutableStateOf(item.keyCode) }
                    val text by remember(item.id) {
                        derivedStateOf {
                            keyCode?.let {
                                KeyEvent.keyCodeToString(it).replace("KEYCODE_", "")
                            } ?: "?"
                        }
                    }

                    DraggableItem(
                        scale = scale,
                        id = item.id,
                        offset = position,
                        onOffsetChange = {
                            item.position += it
                            position += it
                        }
                    ) {
                        BagMapKey(
                            modifier = Modifier
                                .onFocusChanged {
                                    hasFocus = it.hasFocus
                                }
                                .onSizeChanged {
                                    item.size = it.width
                                }
                                .onKeyEvent { keyEvent ->
                                    if (!editingEnabled) return@onKeyEvent false
                                    val nativeKeyCode = keyEvent.key.nativeKeyCode
                                    if (nativeKeyCode >= KeyEvent.KEYCODE_DPAD_UP && nativeKeyCode <= KeyEvent.KEYCODE_DPAD_CENTER)
                                        return@onKeyEvent false
                                    item.keyCode = nativeKeyCode
                                    keyCode = nativeKeyCode
                                    true
                                }
                                .focusRequester(keyFocusRequester)
                                .focusable(enabled = editingEnabled),
                            text = text,
                            borderColor = if (hasFocus) Color.Cyan else MaterialTheme.colorScheme.primary,
                            onClick = { keyFocusRequester.requestFocus() }) {
                            onRemove(item.id)
                        }
                    }
                    DraggableItem(
                        scale = scale,
                        id = item.id,
                        offset = cancelableKeyPosition,
                        onOffsetChange = {
                            item.cancelPosition += it
                            cancelableKeyPosition += it
                        }
                    ) {
                        CancelKey(
                            borderColor = if (hasFocus) Color.Cyan else MaterialTheme.colorScheme.primary,
                            onClick = { keyFocusRequester.requestFocus() }
                        )
                    }
                }
                return@forEach
            }
            DraggableItem(
                scale = scale,
                id = item.id,
                offset = position,
                onOffsetChange = {
                    item.position += it
                    position += it
                }
            ) {
                when (item) {
                    is DraggableItem.VariableKey -> {
                        var hasFocus by remember { mutableStateOf(false) }
                        val keyFocusRequester = remember { FocusRequester() }
                        var keyCode by remember(item.id) { mutableStateOf(item.keyCode) }
                        val text by remember(item.id) {
                            derivedStateOf {
                                keyCode?.let {
                                    KeyEvent.keyCodeToString(it).replace("KEYCODE_", "")
                                } ?: "?"
                            }
                        }
                        LaunchedEffect(editingEnabled) {
                            // Só foca sozinha a tecla ainda sem atalho: assim as teclas já ligadas
                            // (ex.: o WASD) não são trocadas sem querer ao apertar uma tecla.
                            if (editingEnabled && item.keyCode == null) keyFocusRequester.requestFocus()
                        }

                        KeyCircular(
                            value = text,
                            onRemove = {
                                onRemove(item.id)
                            },
                            onClick = { keyFocusRequester.requestFocus() },
                            borderColor = if (hasFocus) Color.Cyan else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .onSizeChanged {
                                    item.size = it.width
                                }
                                .onKeyEvent { keyEvent ->
                                    if (!editingEnabled) return@onKeyEvent false
                                    val nativeKeyCode = keyEvent.key.nativeKeyCode
                                    if (nativeKeyCode >= KeyEvent.KEYCODE_DPAD_UP && nativeKeyCode <= KeyEvent.KEYCODE_DPAD_CENTER)
                                        return@onKeyEvent false
                                    item.keyCode = nativeKeyCode
                                    keyCode = nativeKeyCode
                                    true
                                }
                                .onFocusChanged {
                                    hasFocus = it.hasFocus
                                }
                                .focusRequester(keyFocusRequester)
                                .focusable(enabled = editingEnabled)
                        )
                    }

                    is DraggableItem.WASDGroup -> {
                        WASDKeysGroup(onRemove = {
                            onRemove(item.id)
                        }, modifier = Modifier
                            .onSizeChanged { item.size = it.width },
                            onPanIconDrag = { dragAmount ->
                            userScale = (userScale + (dragAmount.y / 500)).coerceIn(0.5f, 2f)
                            item.scale = userScale
                        })
                    }

                    is DraggableItem.FixedKey -> {
                        val onSizeChangeListener = { intSize: IntSize ->
                            item.size = intSize.width
                        }
                        when (item.type) {
                            DraggableItemType.FIRE -> {
                                FireKey(modifier = Modifier
                                    .onSizeChanged { onSizeChangeListener(it) })
                                {
                                    onRemove(item.id)
                                }
                            }

                            DraggableItemType.SHOOTING_MODE -> {
                                MousePointer(modifier = Modifier.onSizeChanged {
                                    onSizeChangeListener(it)
                                }) {
                                    onRemove(item.id)
                                }
                            }

                            DraggableItemType.SCOPE -> {
                                IconKey(
                                    modifier =  Modifier
                                        .onSizeChanged { onSizeChangeListener(it) },
                                    removable = true,
                                    onRemove = { onRemove(item.id) },
                                    onClick = null,
                                    borderColor = MaterialTheme.colorScheme.primary
                                ) {
                                    Image(
                                        modifier = Modifier
                                            .fillMaxSize(),
                                        painter = painterResource(R.drawable.scope),
                                        contentDescription = "pointer",
                                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.inverseSurface)
                                    )
                                }
                            }

                            else -> {}
                        }
                    }

                    else -> {}
                }

            }

        }
    }
}

@Composable
fun DraggableItem(
    modifier: Modifier = Modifier,
    id: Int,
    offset: Offset,
    scale: Float,
    onOffsetChange: (Offset) -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier
        .offset {
            IntOffset(
                offset.x.roundToInt(),
                offset.y.roundToInt()
            )
        }
        .scale(scale)
        .pointerInput(id) {
            detectDragGestures(onDragEnd = {
            }) { change, dragAmount ->
                change.consume()
                val newOffset = Offset(dragAmount.x, dragAmount.y) * scale
                onOffsetChange(newOffset)
            }
        }) {
        content()
    }
}

@Composable
fun MenuItems(
    modifier: Modifier = Modifier,
    gameName: String = "",
    onAddWasdKeys: () -> Unit = {},
    onItemClick: (DraggableItemType) -> Unit
) {
    val state = rememberLazyGridState()
    state.interactionSource.interactions
    Column(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surface,
            CircleShape.copy(CornerSize(10))
        )
    ) {
        Text(
            modifier = Modifier.padding(top = 4.dp, start = 8.dp),
            text = if (gameName.isBlank()) "Adicionar ao keymap:" else "Keymap de $gameName",
            style = MaterialTheme.typography.labelSmall
        )
        LazyVerticalGrid(
            modifier = Modifier
                .heightIn(max = 300.dp)
                .widthIn(max = 170.dp)
                .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            columns = GridCells.Adaptive(50.dp)
        ) {
            val itemModifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.Black)
            item(contentType = DraggableItemType.KEY) {
                Column(
                    Modifier.clickable(onClick = { onItemClick(DraggableItemType.KEY) }),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(itemModifier, contentAlignment = Alignment.Center) {
                        Text("A", color = Color.Cyan)
                    }
                    Text(
                        "Key",
                        style = TextStyle(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            item(contentType = "wasd_keys") {
                // Atalho: cria W, A, S e D como 4 teclas comuns já ligadas (cada uma vira um ponto da tela).
                Column(
                    Modifier.clickable(onClick = onAddWasdKeys),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(itemModifier, contentAlignment = Alignment.Center) {
                        Text("WASD", color = Color.Cyan, style = TextStyle(fontSize = 9.sp))
                    }
                    Text(
                        "4 teclas",
                        style = TextStyle(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            item(contentType = DraggableItemType.WASD_KEY) {
                Column(
                    Modifier.clickable(onClick = { onItemClick(DraggableItemType.WASD_KEY) }),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(itemModifier, contentAlignment = Alignment.Center) {
                        Text(
                            "W\nS",
                            color = Color.Cyan,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            "A   D",
                            color = Color.Cyan,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        "Compass",
                        style = TextStyle(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            item(contentType = DraggableItemType.SHOOTING_MODE) {
                Column(
                    Modifier.clickable(onClick = { onItemClick(DraggableItemType.SHOOTING_MODE) }),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(itemModifier, contentAlignment = Alignment.Center) {
                        Image(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp),
                            painter = painterResource(R.drawable.move),
                            contentDescription = "Enter Shooting Mode in FPS games Using Right Mouse Click",
                            colorFilter = ColorFilter.tint(
                                Color.Cyan
                            )
                        )
                    }
                    Text(
                        "Shooting Mode",
                        style = TextStyle(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            item(contentType = DraggableItemType.FIRE) {
                Column(
                    Modifier.clickable(onClick = { onItemClick(DraggableItemType.FIRE) }),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(itemModifier, contentAlignment = Alignment.Center) {
                        Image(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            painter = painterResource(R.drawable.bullet),
                            contentDescription = "Fire In FPS Games Using Left Mouse Click",
                            colorFilter = ColorFilter.tint(
                                Color.Cyan
                            )
                        )
                    }
                    Text(
                        "Fire",
                        style = TextStyle(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            item(contentType = DraggableItemType.BAG_MAP) {
                Column(
                    Modifier.clickable { onItemClick(DraggableItemType.BAG_MAP) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconKey(
                        modifier = itemModifier,
                        removable = false,
                        background = Color.Black,
                        borderColor = Color.Transparent,
                        onRemove = {}, onClick = { onItemClick(DraggableItemType.BAG_MAP) }) {
                        Image(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(1.dp),
                            painter = painterResource(R.drawable.bag),
                            contentDescription = "Bag and Map ",
                            colorFilter = ColorFilter.tint(Color.Cyan)
                        )
                    }
                    Text(
                        "Bag and Map",
                        style = TextStyle(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            item(contentType = DraggableItemType.SCOPE) {
                Column(
                    Modifier.clickable { onItemClick(DraggableItemType.SCOPE) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconKey(
                        modifier = itemModifier,
                        removable = false,
                        background = Color.Black,
                        borderColor = Color.Transparent,
                        onRemove = {}, onClick = { onItemClick(DraggableItemType.SCOPE) }) {
                        Image(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            painter = painterResource(R.drawable.scope),
                            contentDescription = "Scope",
                            colorFilter = ColorFilter.tint(Color.Cyan)
                        )
                    }
                    Text(
                        "Scope",
                        style = TextStyle(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }


        }
    }

}


@Composable
fun CursorOverlay(
    position: Offset,
    style: CursorStyle,
    sizeDp: Float,
    color: Int,
    opacity: Float,
    imagePath: String? = null,
    imageVersion: Long = 0L,
    imageHotspotCenter: Boolean = false,
    windowOrigin: Offset = Offset.Zero
) {
    // `position` está em coordenadas da TELA (é o ponto onde o toque é injetado);
    // o overlay desenha em coordenadas da JANELA, então desconta a origem da janela.
    val local = position - windowOrigin

    val imageBitmap = remember(imagePath, imageVersion) {
        imagePath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    if (style == CursorStyle.IMAGE && imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.TopStart,
            modifier = Modifier
                .offset {
                    val cursorPx = sizeDp.dp.toPx()
                    IntOffset(
                        (local.x - if (imageHotspotCenter) cursorPx / 2f else 0f).roundToInt(),
                        (local.y - if (imageHotspotCenter) cursorPx / 2f else 0f).roundToInt()
                    )
                }
                .size(sizeDp.dp)
                .alpha(opacity)
        )
        return
    }

    Canvas(
        modifier = Modifier
            .offset {
                val cursorPx = sizeDp.dp.toPx()
                val centered = style != CursorStyle.ARROW
                IntOffset(
                    (local.x - if (centered) cursorPx / 2f else 0f).roundToInt(),
                    (local.y - if (centered) cursorPx / 2f else 0f).roundToInt()
                )
            }
            .size(sizeDp.dp)
            .alpha(opacity)
    ) {
        val w = size.width
        val h = size.height
        val strokeWidth = (size.minDimension * 0.07f).coerceAtLeast(1.5f)
        val cursorColor = Color(color)
        when (style) {
            CursorStyle.ARROW, CursorStyle.IMAGE -> {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(w * 0.08f, h * 0.91f)
                    lineTo(w * 0.32f, h * 0.68f)
                    lineTo(w * 0.51f, h * 0.96f)
                    lineTo(w * 0.67f, h * 0.86f)
                    lineTo(w * 0.49f, h * 0.58f)
                    lineTo(w * 0.89f, h * 0.58f)
                    close()
                }
                drawPath(path, cursorColor)
                drawPath(path, Color.Black, style = Stroke(width = strokeWidth * 1.7f))
            }
            CursorStyle.CROSSHAIR -> {
                drawCircle(cursorColor, radius = size.minDimension * 0.28f, style = Stroke(strokeWidth))
                drawLine(cursorColor, androidx.compose.ui.geometry.Offset(w / 2f, 0f),
                    androidx.compose.ui.geometry.Offset(w / 2f, h), strokeWidth)
                drawLine(cursorColor, androidx.compose.ui.geometry.Offset(0f, h / 2f),
                    androidx.compose.ui.geometry.Offset(w, h / 2f), strokeWidth)
            }
            CursorStyle.CIRCLE -> {
                drawCircle(cursorColor, radius = size.minDimension * 0.39f, style = Stroke(strokeWidth))
            }
            CursorStyle.DOT -> {
                drawCircle(cursorColor, radius = size.minDimension * 0.18f)
            }
        }
    }
}

@Composable
fun SettingsLayout(
    modifier: Modifier = Modifier,
    pointerSensitivity: Float = 0.5f,
    overlayOpacity: Float = 0.5f,
    onAdvancedSettingsClick: () -> Unit,
    onCloseOverlayClick: () -> Unit,
    onPointerSensChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    cursorStyle: CursorStyle = CursorStyle.ARROW,
    cursorSizeDp: Float = 24f,
    cursorColor: Int = 0xFFFFFFFF.toInt(),
    cursorOpacity: Float = 1f,
    onCursorStyleChange: (CursorStyle) -> Unit = {},
    onCursorSizeChange: (Float) -> Unit = {},
    onCursorColorChange: (Int) -> Unit = {},
    onCursorOpacityChange: (Float) -> Unit = {},
    cursorHasImage: Boolean = false,
    onNextCursorImage: () -> Unit = {},
    showCursorSection: Boolean = false,
    autoTypingMode: Boolean = true,
    onAutoTypingChange: (Boolean) -> Unit = {},
    onTypingModeClick: () -> Unit = {},
    onRecordingModeClick: () -> Unit = {}
) {
    // O painel ficou alto: limita à tela e permite rolar.
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Column(
        modifier
            .heightIn(max = maxPanelHeight)
            .clip(CircleShape.copy(CornerSize(10)))
            .background(color = MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Row {
            Text(
                text = "Pointer Sensitivity: ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text(
                "${(pointerSensitivity * 100).roundToInt()} %",
                modifier = Modifier
                    .padding(start = 16.dp)
                    .align(Alignment.CenterVertically),
                style = TextStyle(color = MaterialTheme.colorScheme.tertiary)
            )
        }

        Slider(
            value = pointerSensitivity,
            onValueChange = onPointerSensChange,
            valueRange = 0.1f..1.0f,
            steps = 8
        )
        Row {
            Text(
                text = "Overlay Opacity: ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text(
                "${(overlayOpacity * 100).roundToInt()} %",
                modifier = Modifier
                    .padding(start = 16.dp)
                    .align(Alignment.CenterVertically),
                style = TextStyle(color = MaterialTheme.colorScheme.tertiary)
            )
        }
        Slider(
            value = overlayOpacity,
            onValueChange = onOpacityChange,
            valueRange = 0.0f..1.0f,
            steps = 9
        )
        if (showCursorSection) {
            HorizontalDivider()
            Text(
                text = "Cursor",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Style", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val styles = CursorStyle.values().filter { it != CursorStyle.IMAGE || cursorHasImage }
                    val next = (styles.indexOf(cursorStyle) + 1) % styles.size
                    onCursorStyleChange(styles[next])
                }) {
                    Text(cursorStyle.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", modifier = Modifier.weight(1f))
                Text("${cursorSizeDp.roundToInt()} dp")
            }
            Slider(
                value = cursorSizeDp,
                onValueChange = onCursorSizeChange,
                valueRange = 12f..64f
            )
            Text("Color", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    0xFFFFFFFF.toInt(),
                    0xFF000000.toInt(),
                    0xFFFF5252.toInt(),
                    0xFF448AFF.toInt(),
                    0xFF69F0AE.toInt(),
                    0xFFFFD740.toInt()
                ).forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(swatch))
                            .clickable { onCursorColorChange(swatch) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity", modifier = Modifier.weight(1f))
                Text("${(cursorOpacity * 100).roundToInt()} %")
            }
            Slider(
                value = cursorOpacity,
                onValueChange = onCursorOpacityChange,
                valueRange = 0.1f..1f
            )
            if (cursorHasImage) {
                TextButton(onClick = onNextCursorImage) { Text("Próxima imagem salva") }
            }
            TextButton(onClick = onAdvancedSettingsClick) {
                Text(if (cursorHasImage) "Gerenciar imagens do cursor…" else "Adicionar imagem de cursor…")
            }
        }
        HorizontalDivider()
        Text(
            text = "Digitar / Gravar",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Modo digitar automático", modifier = Modifier.weight(1f))
            Switch(checked = autoTypingMode, onCheckedChange = onAutoTypingChange)
        }
        TextButton(onClick = onTypingModeClick) { Text("Ligar modo digitar agora") }
        TextButton(onClick = onRecordingModeClick) { Text("Esconder tudo (gravação)") }
        HorizontalDivider()
        TextButton(onClick = onAdvancedSettingsClick) {
            Icon(Icons.Rounded.Settings, contentDescription = "Advanced Settings")
            Text(
                "Advanced Settings",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
        TextButton(onClick = onCloseOverlayClick) {
            Icon(
                Icons.AutoMirrored.Rounded.ExitToApp,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.tertiary
            )
            Text(
                "Close Overlay",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}


@Preview
@Composable
private fun PreviewMenuItems() {
    MenuItems(onItemClick = {})
}

@Preview()
@Composable
private fun PreviewSettingsLayout() {
    SettingsLayout(
        Modifier.clip(
            CircleShape.copy(CornerSize(10))
        ),
        onAdvancedSettingsClick = { },
        onPointerSensChange = { },
        onOpacityChange = { },
        onCloseOverlayClick = {}
    )
}

internal class ServiceLifecycleOwner : SavedStateRegistryOwner {
    private var mSavedStateRegistryController: SavedStateRegistryController =
        SavedStateRegistryController.create(this)

    private var lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = mSavedStateRegistryController.savedStateRegistry

    fun setCurrentState(state: Lifecycle.State) {
        lifecycleRegistry.currentState = state
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore(savedState: Bundle?) {
        mSavedStateRegistryController.performRestore(savedState)
    }

    fun performSave(outBundle: Bundle) {
        mSavedStateRegistryController.performSave(outBundle)
    }
}