package com.wx.mapper.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wx.mapper.ui.theme.WxTheme
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Treino de mira: tela deitada (paisagem) com uma bolinha vermelha que anda aleatoriamente.
 * Modos: CLICAR (acertar a bolinha) e SEGUIR (manter o cursor em cima dela).
 * Funciona com toque, com o mouse e também com o cursor do WX (os cliques viram toques).
 */
class AimTrainerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            WxTheme {
                AimTrainerScreen(onExit = { finish() })
            }
        }
    }
}

private enum class TrainerPhase { SETUP, PLAYING, RESULT }

private enum class TrainerMode { CLICK, FOLLOW }

private val TrainerBackground = Color(0xFF101418)
private val TargetRed = Color(0xFFFF1744)

@Composable
private fun AimTrainerScreen(onExit: () -> Unit) {
    var phase by remember { mutableStateOf(TrainerPhase.SETUP) }
    var speedDp by remember { mutableFloatStateOf(400f) }
    var sizeDp by remember { mutableFloatStateOf(56f) }
    var mode by remember { mutableStateOf(TrainerMode.CLICK) }
    var durationSec by remember { mutableIntStateOf(60) }

    var hits by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var onTargetSeconds by remember { mutableFloatStateOf(0f) }

    BackHandler { if (phase == TrainerPhase.SETUP) onExit() else phase = TrainerPhase.SETUP }

    Box(Modifier.fillMaxSize().background(TrainerBackground)) {
        when (phase) {
            TrainerPhase.SETUP -> SetupPanel(
                speedDp = speedDp,
                onSpeedChange = { speedDp = it },
                sizeDp = sizeDp,
                onSizeChange = { sizeDp = it },
                mode = mode,
                onModeChange = { mode = it },
                durationSec = durationSec,
                onDurationChange = { durationSec = it },
                onStart = {
                    hits = 0
                    misses = 0
                    elapsed = 0f
                    onTargetSeconds = 0f
                    phase = TrainerPhase.PLAYING
                },
                onExit = onExit
            )

            TrainerPhase.PLAYING -> PlayField(
                speedDp = speedDp,
                sizeDp = sizeDp,
                mode = mode,
                durationSec = durationSec,
                hits = hits,
                misses = misses,
                elapsed = elapsed,
                onTargetSeconds = onTargetSeconds,
                onHit = { hits++ },
                onMiss = { misses++ },
                onTick = { dt, onTarget ->
                    elapsed += dt
                    if (onTarget) onTargetSeconds += dt
                },
                onFinish = { phase = TrainerPhase.RESULT },
                onStop = { phase = TrainerPhase.RESULT }
            )

            TrainerPhase.RESULT -> ResultPanel(
                mode = mode,
                hits = hits,
                misses = misses,
                elapsed = elapsed,
                onTargetSeconds = onTargetSeconds,
                onAgain = {
                    hits = 0
                    misses = 0
                    elapsed = 0f
                    onTargetSeconds = 0f
                    phase = TrainerPhase.PLAYING
                },
                onSetup = { phase = TrainerPhase.SETUP },
                onExit = onExit
            )
        }
    }
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text, color = Color.White) }
    }
}

@Composable
private fun BoxScope.SetupPanel(
    speedDp: Float,
    onSpeedChange: (Float) -> Unit,
    sizeDp: Float,
    onSizeChange: (Float) -> Unit,
    mode: TrainerMode,
    onModeChange: (TrainerMode) -> Unit,
    durationSec: Int,
    onDurationChange: (Int) -> Unit,
    onStart: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Treino de mira", color = Color.White, fontSize = 26.sp)

        Text("Modo", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Clicar na bolinha", mode == TrainerMode.CLICK) { onModeChange(TrainerMode.CLICK) }
            ChoiceButton("Seguir com o cursor", mode == TrainerMode.FOLLOW) { onModeChange(TrainerMode.FOLLOW) }
        }

        Text("Velocidade: ${speedDp.roundToInt()} dp/s", color = Color.White)
        Slider(value = speedDp, onValueChange = onSpeedChange, valueRange = 100f..1500f)

        Text("Tamanho: ${sizeDp.roundToInt()} dp", color = Color.White)
        Slider(value = sizeDp, onValueChange = onSizeChange, valueRange = 16f..140f)

        Text("Duração", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30 to "30 s", 60 to "60 s", 120 to "120 s", 0 to "Sem limite").forEach { (value, label) ->
                ChoiceButton(label, durationSec == value) { onDurationChange(value) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onStart) { Text("Começar") }
            OutlinedButton(onClick = onExit) { Text("Sair", color = Color.White) }
        }
    }
}

@Composable
private fun PlayField(
    speedDp: Float,
    sizeDp: Float,
    mode: TrainerMode,
    durationSec: Int,
    hits: Int,
    misses: Int,
    elapsed: Float,
    onTargetSeconds: Float,
    onHit: () -> Unit,
    onMiss: () -> Unit,
    onTick: (Float, Boolean) -> Unit,
    onFinish: () -> Unit,
    onStop: () -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { sizeDp.dp.toPx() } / 2f
    val speedPx = with(density) { speedDp.dp.toPx() }
    val hudPx = with(density) { 64.dp.toPx() }

    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    var ball by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf<Offset?>(null) }

    fun randomPoint(): Offset {
        val w = fieldSize.width.toFloat()
        val h = fieldSize.height.toFloat()
        val minX = radiusPx
        val maxX = (w - radiusPx).coerceAtLeast(minX)
        val minY = (radiusPx + hudPx).coerceAtMost(h)
        val maxY = (h - radiusPx).coerceAtLeast(minY)
        return Offset(
            minX + Random.nextFloat() * (maxX - minX),
            minY + Random.nextFloat() * (maxY - minY)
        )
    }

    LaunchedEffect(fieldSize, speedPx, radiusPx, mode, durationSec) {
        if (fieldSize == IntSize.Zero) return@LaunchedEffect
        ball = randomPoint()
        target = randomPoint()
        var last = androidx.compose.runtime.withFrameNanos { it }
        var turnIn = 1f
        var total = 0f
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            total += dt
            turnIn -= dt

            val delta = target - ball
            val dist = delta.getDistance()
            val step = speedPx * dt
            if (dist <= step || turnIn <= 0f) {
                // Chegou (ou deu a hora de mudar de ideia): escolhe outro ponto aleatório.
                target = randomPoint()
                turnIn = 0.5f + Random.nextFloat() * 1.2f
            } else {
                ball += delta / dist * step
            }

            val p = pointer
            val onTarget = mode == TrainerMode.FOLLOW && p != null && (p - ball).getDistance() <= radiusPx
            onTick(dt, onTarget)
            if (durationSec > 0 && total >= durationSec) {
                onFinish()
                return@LaunchedEffect
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { fieldSize = it }
            .pointerInput(mode, radiusPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        pointer = if (event.type == PointerEventType.Exit) null else change.position
                        if (mode == TrainerMode.CLICK && event.type == PointerEventType.Press) {
                            if ((change.position - ball).getDistance() <= radiusPx) {
                                onHit()
                                // Acertou: a bolinha pula para outro lugar.
                                ball = randomPoint()
                                target = randomPoint()
                            } else {
                                onMiss()
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(TargetRed, radius = radiusPx, center = ball)
            drawCircle(
                Color.White.copy(alpha = 0.35f),
                radius = radiusPx,
                center = ball,
                style = Stroke(width = 3f)
            )
        }

        val shots = hits + misses
        val stat = if (mode == TrainerMode.CLICK) {
            val acc = if (shots == 0) 0 else (hits * 100f / shots).roundToInt()
            "Acertos $hits   Erros $misses   Precisão $acc%"
        } else {
            val pct = if (elapsed <= 0f) 0 else (onTargetSeconds * 100f / elapsed).roundToInt()
            "Na bolinha $pct%"
        }
        val timeText = if (durationSec > 0) {
            "${(durationSec - elapsed).coerceAtLeast(0f).roundToInt()} s"
        } else {
            "${elapsed.roundToInt()} s"
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$stat   •   $timeText", color = Color.White, fontSize = 16.sp)
            OutlinedButton(onClick = onStop) { Text("Parar", color = Color.White) }
        }
    }
}

@Composable
private fun BoxScope.ResultPanel(
    mode: TrainerMode,
    hits: Int,
    misses: Int,
    elapsed: Float,
    onTargetSeconds: Float,
    onAgain: () -> Unit,
    onSetup: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Resultado", color = Color.White, fontSize = 26.sp)
        if (mode == TrainerMode.CLICK) {
            val shots = hits + misses
            val acc = if (shots == 0) 0 else (hits * 100f / shots).roundToInt()
            Text("Acertos: $hits", color = Color.White)
            Text("Erros: $misses", color = Color.White)
            Text("Precisão: $acc%", color = Color.White)
        } else {
            val pct = if (elapsed <= 0f) 0 else (onTargetSeconds * 100f / elapsed).roundToInt()
            Text("Tempo na bolinha: $pct%", color = Color.White)
        }
        Text("Tempo: ${elapsed.roundToInt()} s", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onAgain) { Text("Jogar de novo") }
            OutlinedButton(onClick = onSetup) { Text("Configurar", color = Color.White) }
            OutlinedButton(onClick = onExit) { Text("Sair", color = Color.White) }
        }
    }
}
