package com.wx.mapper.ui.overlay

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.wx.mapper.music.MusicPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wx.mapper.autoclicker.AutoClickerConfig
import com.wx.mapper.model.CursorStyle
import java.io.File
import kotlin.math.roundToInt

/** Um jogo cadastrado, para a lista de perfis da bolinha. */
data class GameEntry(val packageName: String, val label: String)

/* ------------------------------------------------------------------ */
/* Pedaços reutilizáveis                                                */
/* ------------------------------------------------------------------ */

/** Botão redondo da barra lateral da bolinha. Fica com destaque quando o painel dele está aberto. */
@Composable
fun RailButton(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Ícone de cursor (setinha) desenhado à mão, sem depender de pacote de ícones extra. */
@Composable
fun CursorGlyph(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.15f, h * 0.05f)
            lineTo(w * 0.23f, h * 0.92f)
            lineTo(w * 0.42f, h * 0.70f)
            lineTo(w * 0.58f, h * 0.98f)
            lineTo(w * 0.72f, h * 0.90f)
            lineTo(w * 0.56f, h * 0.62f)
            lineTo(w * 0.86f, h * 0.60f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PanelSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Column(
        modifier = modifier
            .widthIn(max = 260.dp)
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        content = content
    )
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/* ------------------------------------------------------------------ */
/* Painel: perfil do jogo                                               */
/* ------------------------------------------------------------------ */

@Composable
fun ProfilePanel(
    modifier: Modifier = Modifier,
    games: List<GameEntry>,
    activePackage: String?,
    autoClicker: AutoClickerConfig,
    pointerSensitivity: Float,
    onSelectGame: (String) -> Unit,
    onAutoClickerChange: (AutoClickerConfig) -> Unit,
    onPointerSensChange: (Float) -> Unit,
    onManageProfiles: () -> Unit
) {
    PanelSurface(modifier) {
        PanelTitle("Perfil do jogo")
        if (games.isEmpty()) {
            Text(
                "Nenhum jogo cadastrado ainda. Adicione pelo app.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        games.forEach { game ->
            val active = game.packageName == activePackage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .clickable { onSelectGame(game.packageName) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Text(
                    text = game.label,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        if (activePackage != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            PanelTitle("Autoclicker")
            SwitchRow("Ligado", autoClicker.enabled) {
                onAutoClickerChange(autoClicker.copy(enabled = it))
            }
            if (autoClicker.enabled) {
                SwitchRow("Modo alternar", autoClicker.toggleMode) {
                    onAutoClickerChange(autoClicker.copy(toggleMode = it))
                }
                LabeledSlider(
                    label = "Cliques por segundo",
                    valueText = "${autoClicker.cps.roundToInt()} CPS",
                    value = autoClicker.cps,
                    range = AutoClickerConfig.MIN_CPS..AutoClickerConfig.MAX_CPS
                ) { onAutoClickerChange(autoClicker.copy(cps = it.roundToInt().toFloat())) }
                LabeledSlider(
                    label = "Tempo do toque",
                    valueText = if (autoClicker.holdMs == 0L) "instantâneo" else "${autoClicker.holdMs} ms",
                    value = autoClicker.holdMs.toFloat(),
                    range = AutoClickerConfig.MIN_HOLD_MS.toFloat()..AutoClickerConfig.MAX_HOLD_MS.toFloat()
                ) { onAutoClickerChange(autoClicker.copy(holdMs = it.roundToInt().toLong())) }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            LabeledSlider(
                label = "Sensibilidade",
                valueText = com.wx.mapper.util.Sensitivity.format(pointerSensitivity),
                value = com.wx.mapper.util.Sensitivity.toSlider(pointerSensitivity),
                range = 0f..1f,
                onChange = { onPointerSensChange(com.wx.mapper.util.Sensitivity.fromSlider(it)) }
            )
        }
        TextButton(onClick = onManageProfiles) { Text("Gerenciar perfis no app…") }
    }
}

/* ------------------------------------------------------------------ */
/* Painel: cursor                                                       */
/* ------------------------------------------------------------------ */

private fun styleLabel(style: CursorStyle): String = when (style) {
    CursorStyle.ARROW -> "Seta"
    CursorStyle.CROSSHAIR -> "Mira"
    CursorStyle.CIRCLE -> "Círculo"
    CursorStyle.DOT -> "Ponto"
    CursorStyle.IMAGE -> "PNG"
}

private val SWATCHES = listOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFFFF5252.toInt(),
    0xFF448AFF.toInt(),
    0xFF69F0AE.toInt(),
    0xFFFFD740.toInt()
)

private fun decodeThumb(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()

@Composable
private fun CursorThumb(file: File, selected: Boolean, onClick: () -> Unit) {
    val bitmap = remember(file.absolutePath, file.lastModified()) { decodeThumb(file) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(shape)
            .background(Color(0xFF2B2B2B))
            .border(
                2.dp,
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape
            )
            .clickable(onClick = onClick)
            .padding(5.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun CursorPanel(
    modifier: Modifier = Modifier,
    style: CursorStyle,
    sizeDp: Float,
    color: Int,
    opacity: Float,
    images: List<File>,
    selectedImagePath: String?,
    hotspotCenter: Boolean,
    onStyleChange: (CursorStyle) -> Unit,
    onSizeChange: (Float) -> Unit,
    onColorChange: (Int) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onSelectImage: (File) -> Unit,
    onHotspotChange: (Boolean) -> Unit,
    onAddImage: () -> Unit
) {
    PanelSurface(modifier) {
        PanelTitle("Cursor")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CursorStyle.values()
                .filter { it != CursorStyle.IMAGE || images.isNotEmpty() }
                .forEach { option ->
                    OptionChip(styleLabel(option), option == style) { onStyleChange(option) }
                }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        PanelTitle("Imagem PNG")
        if (images.isEmpty()) {
            Text(
                "Nenhuma imagem salva ainda.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                images.forEach { file ->
                    CursorThumb(
                        file = file,
                        selected = style == CursorStyle.IMAGE && file.absolutePath == selectedImagePath
                    ) { onSelectImage(file) }
                }
            }
            SwitchRow("Ponta no centro da imagem", hotspotCenter, onHotspotChange)
        }
        TextButton(onClick = onAddImage) {
            Text(if (images.isEmpty()) "Adicionar imagem…" else "Gerenciar imagens…")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        LabeledSlider(
            label = "Tamanho",
            valueText = "${sizeDp.roundToInt()} dp",
            value = sizeDp,
            range = 12f..64f,
            onChange = onSizeChange
        )
        LabeledSlider(
            label = "Opacidade",
            valueText = "${(opacity * 100).roundToInt()} %",
            value = opacity,
            range = 0.1f..1f,
            onChange = onOpacityChange
        )
        if (style != CursorStyle.IMAGE) {
            Text("Cor", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SWATCHES.forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(swatch))
                            .border(
                                if (swatch == color) 2.dp else 1.dp,
                                if (swatch == color) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                            .clickable { onColorChange(swatch) }
                    )
                }
            }
        }
    }
}


/** Controles de música no overlay: dá para trocar de faixa/playlist sem sair do jogo. */
@Composable
fun MusicPanel(onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val st by MusicPlayer.state.collectAsState()
    val lib by MusicPlayer.library.collectAsState()
    val access = MusicPlayer.hasAccess(ctx)
    LaunchedEffect(access) {
        if (access && !MusicPlayer.scanned) withContext(Dispatchers.IO) { MusicPlayer.scan() }
    }
    PanelSurface(modifier) {
        PanelTitle("Música")
        if (!access) {
            Text("Falta permissão para ler /msc.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpenApp) { Text("Abrir Música e permitir") }
            return@PanelSurface
        }
        Text(
            text = if (st.title.isEmpty()) "Nada tocando" else st.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OptionChip("◀◀", false) { MusicPlayer.prev() }
            OptionChip(if (st.playing) "❚❚" else "▶", st.playing) { MusicPlayer.toggle() }
            OptionChip("▶▶", false) { MusicPlayer.next() }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            OptionChip("Aleatório", st.shuffle) { MusicPlayer.toggleShuffle() }
            OptionChip("Repetir 1", st.repeatOne) { MusicPlayer.toggleRepeat() }
        }
        Text(
            "Volume ${(st.volume * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
        Slider(value = st.volume, onValueChange = { MusicPlayer.setVolume(it) })
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        if (lib.isEmpty()) {
            Text(
                if (MusicPlayer.scanned) "Nenhuma música em /storage/emulated/0/msc/" else "Procurando…",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            lib.keys.forEach { name ->
                OptionChip("$name (${lib[name]?.size ?: 0})", st.playlist == name) { MusicPlayer.playPlaylist(name) }
                Box(Modifier.size(4.dp))
            }
        }
        Text(
            "Atalhos: Ctrl+Alt+Espaço / ← / → / ↑ / ↓",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
