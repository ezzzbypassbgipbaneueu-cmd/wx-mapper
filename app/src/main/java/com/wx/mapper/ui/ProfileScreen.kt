package com.wx.mapper.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wx.mapper.domain.KEYCODE_LMC
import com.wx.mapper.domain.KEYCODE_MMC
import com.wx.mapper.domain.KEYCODE_RMC
import com.wx.mapper.model.GameProfile
import com.wx.mapper.model.GameSummary
import com.wx.mapper.ui.theme.GlassCard
import com.wx.mapper.ui.theme.WxBackground
import com.wx.mapper.ui.theme.WxPurple
import kotlin.math.roundToInt

private fun triggerLabel(code: Int) = when (code) {
    KEYCODE_LMC -> "botão esquerdo"
    KEYCODE_RMC -> "botão direito"
    KEYCODE_MMC -> "botão do meio"
    else -> "tecla"
}

@Composable
private fun SectionTitle(text: String, hint: String? = null) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (hint != null) {
        Spacer(Modifier.size(2.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}

/**
 * Tela do perfil de UM jogo: resumo do keymap, autoclicker, macros, mouse, cópia de outro jogo.
 * Tudo que é editado aqui vale só para este jogo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    gameName: String,
    packageName: String,
    icon: Drawable?,
    profile: GameProfile,
    summary: GameSummary?,
    otherGames: List<Pair<String, String>>,
    onNavigateBack: () -> Unit,
    onPlay: () -> Unit,
    onOpenAutoclicker: () -> Unit,
    onOpenMacros: () -> Unit,
    onSaveProfile: (GameProfile) -> Unit,
    onCopyFrom: (fromPackage: String, layout: Boolean, macros: Boolean, autoClicker: Boolean, mouse: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    var showCopy by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var sensitivity by remember(profile.pointerSensitivity) { mutableFloatStateOf(profile.pointerSensitivity) }
    var opacity by remember(profile.overlayOpacity) { mutableFloatStateOf(profile.overlayOpacity) }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(WxBackground),
        topBar = {
            TopAppBar(
                title = { Text("Perfil do jogo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = icon,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(gameName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
                Button(
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = WxPurple, contentColor = Color.White)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Abrir jogo com este perfil")
                }
            }

            GlassCard {
                SectionTitle(
                    "Keymap",
                    "Abra o jogo, toque na bolha do WX e arraste os botões. O que você montar fica salvo só neste jogo."
                )
                Spacer(Modifier.size(8.dp))
                val buttons = summary?.buttons ?: 0
                Text(
                    if (buttons == 0) "Nenhum botão ainda" else "$buttons botão(ões) no mapa",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Autoclicker")
                        Text(
                            "${profile.autoClicker.cps.roundToInt()} CPS · " +
                                    (if (profile.autoClicker.toggleMode) "alternar" else "segurar") +
                                    " ${triggerLabel(profile.autoClicker.triggerKeyCode)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Switch(
                        checked = profile.autoClicker.enabled,
                        onCheckedChange = { onSaveProfile(profile.copy(autoClicker = profile.autoClicker.copy(enabled = it))) }
                    )
                }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = onOpenAutoclicker) { Text("Configurar autoclicker") }
            }

            GlassCard {
                SectionTitle("Macros", "${profile.macros.size} macro(s) neste jogo")
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = onOpenMacros) { Text("Gerenciar macros") }
            }

            GlassCard {
                SectionTitle("Mouse e botões")
                Spacer(Modifier.size(6.dp))
                Text("Sensibilidade: ${com.wx.mapper.util.Sensitivity.format(sensitivity)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = com.wx.mapper.util.Sensitivity.toSlider(sensitivity),
                    onValueChange = { sensitivity = com.wx.mapper.util.Sensitivity.fromSlider(it) },
                    onValueChangeFinished = { onSaveProfile(profile.copy(pointerSensitivity = sensitivity, overlayOpacity = opacity)) },
                    valueRange = 0f..1f
                )
                Text("Opacidade dos botões: ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { onSaveProfile(profile.copy(pointerSensitivity = sensitivity, overlayOpacity = opacity)) },
                    valueRange = 0.1f..1f
                )
            }

            GlassCard {
                SectionTitle(
                    "Copiar de outro jogo",
                    "Reaproveite o keymap, as macros ou o autoclicker de um jogo que você já configurou."
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { showCopy = true }, enabled = otherGames.isNotEmpty()) {
                    Text(if (otherGames.isEmpty()) "Adicione outro jogo para copiar" else "Copiar de…")
                }
            }

            GlassCard {
                SectionTitle("Resetar", "Apaga o keymap, as macros e o autoclicker só deste jogo.")
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { showReset = true }) {
                    Text("Resetar perfil", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Resetar perfil de $gameName?") },
            text = { Text("O keymap, as macros e o autoclicker deste jogo voltam ao padrão. Não dá para desfazer.") },
            confirmButton = {
                TextButton(onClick = { showReset = false; onReset() }) {
                    Text("Resetar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancelar") } }
        )
    }

    if (showCopy) {
        var from by remember { mutableStateOf(otherGames.firstOrNull()?.first) }
        var layout by remember { mutableStateOf(true) }
        var macros by remember { mutableStateOf(true) }
        var auto by remember { mutableStateOf(true) }
        var mouse by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showCopy = false },
            title = { Text("Copiar para $gameName") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("De qual jogo?", style = MaterialTheme.typography.labelLarge)
                    otherGames.forEach { (pkg, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { from = pkg },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = from == pkg, onClick = { from = pkg })
                            Text(name)
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("O que copiar?", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        Triple("Keymap (botões)", layout, { v: Boolean -> layout = v }),
                        Triple("Macros", macros, { v: Boolean -> macros = v }),
                        Triple("Autoclicker", auto, { v: Boolean -> auto = v }),
                        Triple("Mouse e opacidade", mouse, { v: Boolean -> mouse = v }),
                    ).forEach { (label, checked, set) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { set(!checked) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { set(it) })
                            Text(label)
                        }
                    }
                    Text(
                        "O que for copiado substitui o que este jogo já tem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = from != null && (layout || macros || auto || mouse),
                    onClick = {
                        from?.let { onCopyFrom(it, layout, macros, auto, mouse) }
                        showCopy = false
                    }
                ) { Text("Copiar") }
            },
            dismissButton = { TextButton(onClick = { showCopy = false }) { Text("Cancelar") } }
        )
    }
}
