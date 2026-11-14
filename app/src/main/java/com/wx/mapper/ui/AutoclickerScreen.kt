package com.wx.mapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wx.mapper.autoclicker.AutoClickerConfig
import com.wx.mapper.autoclicker.ClickPositionMode
import com.wx.mapper.domain.KEYCODE_LMC
import com.wx.mapper.domain.KEYCODE_MMC
import com.wx.mapper.domain.KEYCODE_RMC
import com.wx.mapper.input.WxRuntime
import com.wx.mapper.model.AppConfig
import com.wx.mapper.ui.theme.GlassCard
import com.wx.mapper.ui.theme.WxBackground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
object AutoclickerScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoclickerScreen(
    appConfig: AppConfig,
    onNavigateBack: () -> Unit,
    onSave: (AppConfig) -> Unit,
) {
    var cfg by remember { mutableStateOf(appConfig.autoClicker) }
    val actions by WxRuntime.actions.collectAsState()
    val runningFlow = remember(actions) { actions?.autoClicker?.isRunning ?: MutableStateFlow(false) }
    val running by runningFlow.collectAsState()

    fun update(new: AutoClickerConfig) {
        cfg = new.sanitized()
        onSave(appConfig.copy(autoClicker = cfg)) // persiste a cada mudança
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(WxBackground),
        topBar = {
            TopAppBar(
                title = { Text("Autoclicker") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(if (running) Color(0xFF22C55E) else Color.Gray, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Clicando agora" else if (actions == null) "Overlay desligado" else "Parado")
                    Spacer(Modifier.weight(1f))
                    Switch(checked = cfg.enabled, onCheckedChange = { update(cfg.copy(enabled = it)) })
                }
                if (actions == null) {
                    Text(
                        "O autoclicker só age dentro do jogo: abra o jogo pelo launcher do WX MAPPER (Shizuku ativo).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            GlassCard {
                Text("Velocidade: ${cfg.cps.roundToInt()} CPS")
                Slider(
                    value = cfg.cps,
                    onValueChange = { cfg = cfg.copy(cps = it) },
                    onValueChangeFinished = { update(cfg) },
                    valueRange = AutoClickerConfig.MIN_CPS..AutoClickerConfig.MAX_CPS
                )
                Text("Limite: ${AutoClickerConfig.MIN_CPS.roundToInt()}–${AutoClickerConfig.MAX_CPS.roundToInt()} CPS", style = MaterialTheme.typography.bodySmall)
            }

            GlassCard {
                Text(if (cfg.holdMs == 0L) "Tempo do toque: instantâneo (0 ms)" else "Tempo do toque: ${cfg.holdMs} ms")
                Slider(
                    value = cfg.holdMs.toFloat(),
                    onValueChange = { cfg = cfg.copy(holdMs = it.roundToInt().toLong()) },
                    onValueChangeFinished = { update(cfg) },
                    valueRange = AutoClickerConfig.MIN_HOLD_MS.toFloat()..AutoClickerConfig.MAX_HOLD_MS.toFloat()
                )
                Text("Quanto o toque fica apertado. 0 = aperta e solta em sequência, sem espera. Se o jogo não registrar os cliques, suba um pouco.", style = MaterialTheme.typography.bodySmall)
            }

            GlassCard {
                Text("Modo de ativação")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = cfg.toggleMode,
                        onClick = { update(cfg.copy(toggleMode = true)) },
                        label = { Text("Alternar") })
                    FilterChip(
                        selected = !cfg.toggleMode,
                        onClick = { update(cfg.copy(toggleMode = false)) },
                        label = { Text("Segurar") })
                }
                Text(
                    if (cfg.toggleMode) "Clique no botão para LIGAR o autoclicker e clique de novo para PARAR."
                    else "O autoclicker clica só enquanto o botão estiver pressionado.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Botão de ativação")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(KEYCODE_LMC to "Esquerdo", KEYCODE_RMC to "Direito", KEYCODE_MMC to "Meio").forEach { (code, label) ->
                        FilterChip(
                            selected = cfg.triggerKeyCode == code,
                            onClick = { update(cfg.copy(triggerKeyCode = code)) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            GlassCard {
                Text("Posição do clique")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = cfg.positionMode == ClickPositionMode.FOLLOW_POINTER,
                        onClick = { update(cfg.copy(positionMode = ClickPositionMode.FOLLOW_POINTER)) },
                        label = { Text("Seguir cursor") })
                    FilterChip(
                        selected = cfg.positionMode == ClickPositionMode.FIXED,
                        onClick = { update(cfg.copy(positionMode = ClickPositionMode.FIXED)) },
                        label = { Text("Ponto fixo") })
                }
                if (cfg.positionMode == ClickPositionMode.FIXED) {
                    var x by remember { mutableStateOf(cfg.fixedX.roundToInt().toString()) }
                    var y by remember { mutableStateOf(cfg.fixedY.roundToInt().toString()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(x, { x = it.filter(Char::isDigit).take(5) }, label = { Text("X (px)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(y, { y = it.filter(Char::isDigit).take(5) }, label = { Text("Y (px)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = {
                        update(cfg.copy(fixedX = x.toFloatOrNull() ?: 0f, fixedY = y.toFloatOrNull() ?: 0f))
                    }) { Text("Aplicar ponto") }
                }
            }
        }
    }
}
