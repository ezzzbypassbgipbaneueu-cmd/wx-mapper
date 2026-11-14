package com.wx.mapper.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import com.wx.mapper.input.WxRuntime
import com.wx.mapper.macros.Macro
import com.wx.mapper.macros.MacroAction
import com.wx.mapper.model.AppConfig
import com.wx.mapper.ui.theme.GlassCard
import com.wx.mapper.ui.theme.WxBackground
import com.wx.mapper.util.keyCodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable
object MacrosScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacrosScreen(
    appConfig: AppConfig,
    onNavigateBack: () -> Unit,
    onSave: (AppConfig) -> Unit,
) {
    var macros by remember { mutableStateOf(appConfig.macros) }
    // Macros gravadas pelo overlay chegam por appConfig com ids novos (maiores que os já vistos).
    var seenMaxId by remember { mutableIntStateOf(appConfig.macros.maxOfOrNull { it.id } ?: 0) }
    LaunchedEffect(appConfig.macros) {
        val fresh = appConfig.macros.filter { it.id > seenMaxId }
        if (fresh.isNotEmpty()) {
            macros = macros + fresh
            seenMaxId = fresh.maxOf { it.id }
        }
    }
    var editingId by remember { mutableStateOf<Int?>(null) }
    val actions by WxRuntime.actions.collectAsState()
    val playingFlow = remember(actions) { actions?.macroPlayer?.playingMacroId ?: MutableStateFlow<Int?>(null) }
    val playing by playingFlow.collectAsState()
    val errorFlow = remember(actions) { actions?.macroPlayer?.lastError ?: MutableStateFlow<String?>(null) }
    val playerError by errorFlow.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    fun commit(new: List<Macro>) {
        macros = new
        onSave(appConfig.copy(macros = new))
    }

    val editing = macros.firstOrNull { it.id == editingId }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(WxBackground),
        topBar = {
            TopAppBar(
                title = { Text("Macros") },
                navigationIcon = {
                    IconButton(onClick = { if (editing != null) editingId = null else onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        floatingActionButton = {
            if (editing == null) FloatingActionButton(onClick = {
                val id = (macros.maxOfOrNull { it.id } ?: 0) + 1
                seenMaxId = maxOf(seenMaxId, id)
                commit(macros + Macro(id = id, name = "Macro $id"))
                editingId = id
            }) { Icon(Icons.Rounded.Add, contentDescription = "Nova macro") }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (message ?: playerError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (actions == null) Text(
                "Para reproduzir, abra um jogo pelo launcher (overlay + Shizuku). Teclas de atalho também só funcionam lá.",
                style = MaterialTheme.typography.bodySmall
            )
            if (editing == null) {
                GlassCard {
                    Text("Como criar uma macro sem digitar nada", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. Abra o jogo pelo launcher.\n" +
                        "2. Aperte F8 — aparece \"GRAVANDO\" na tela.\n" +
                        "3. Faça o que quer repetir: cliques do mouse esquerdo e teclas que você mapeou.\n" +
                        "4. Aperte F8 de novo. A macro é salva aqui, já com uma tecla pronta (ex.: F7).\n" +
                        "5. No jogo, aperte essa tecla para rodar e aperte de novo para parar.\n" +
                        "Depois, aqui, você pode ligar o loop, deixar 2x mais rápida/lenta ou ajustar cada toque.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (macros.isEmpty()) Text("Nenhuma macro ainda. Grave com F8 ou toque em + para criar na mão.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(macros, key = { it.id }) { m ->
                        GlassCard {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                TextButton(onClick = { editingId = m.id }, modifier = Modifier.weight(1f)) {
                                    Text("${m.name} · ${m.actions.size} ações" +
                                        (m.triggerKeyCode?.let { " · ${it.keyCodeToString()}" } ?: ""))
                                }
                                IconButton(onClick = {
                                    val a = actions
                                    if (a == null) message = "Overlay desligado: abra um jogo pelo launcher."
                                    else { message = null; a.macroPlayer.toggle(m) }
                                }) {
                                    Icon(if (playing == m.id) Icons.Rounded.Close else Icons.Rounded.PlayArrow, "Reproduzir/parar")
                                }
                                IconButton(onClick = {
                                    if (playing == m.id) actions?.macroPlayer?.stop()
                                    commit(macros.filterNot { it.id == m.id })
                                }) { Icon(Icons.Rounded.Delete, "Excluir") }
                            }
                        }
                    }
                }
            } else {
                MacroEditor(editing, { upd ->
                    commit(macros.map { if (it.id == upd.id) upd else it })
                    message = upd.validate()
                })
            }
        }
    }
}

@Composable
private fun MacroEditor(macro: Macro, onChange: (Macro) -> Unit) {
    var capturing by remember { mutableStateOf(false) }
    var x by remember { mutableStateOf("") }
    var y by remember { mutableStateOf("") }
    var ms by remember { mutableStateOf("100") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(capturing) { if (capturing) focus.requestFocus() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard {
                OutlinedTextField(macro.name, { onChange(macro.copy(name = it.take(40))) },
                    label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Repetir em loop", Modifier.weight(1f))
                    Switch(macro.loop, { onChange(macro.copy(loop = it)) })
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { capturing = true },
                        modifier = Modifier.focusRequester(focus).onKeyEvent {
                            if (capturing && it.type == KeyEventType.KeyDown) {
                                onChange(macro.copy(triggerKeyCode = it.key.nativeKeyCode)); capturing = false; true
                            } else false
                        }
                    ) {
                        Text(if (capturing) "Aperte uma tecla…" else "Tecla: ${macro.triggerKeyCode?.keyCodeToString() ?: "nenhuma"}")
                    }
                    if (macro.triggerKeyCode != null) TextButton({ onChange(macro.copy(triggerKeyCode = null)) }) { Text("Limpar") }
                }
                if (macro.actions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onChange(macro.scaled(0.5)) }) { Text("2x mais rápida") }
                        OutlinedButton(onClick = { onChange(macro.scaled(2.0)) }) { Text("2x mais lenta") }
                    }
                }
            }
        }
        item {
            GlassCard {
                Text("Adicionar ação")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(x, { x = it.filter(Char::isDigit).take(5) }, label = { Text("X") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(y, { y = it.filter(Char::isDigit).take(5) }, label = { Text("Y") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(ms, { ms = it.filter(Char::isDigit).take(6) }, label = { Text("ms") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val fx = x.toFloatOrNull(); val fy = y.toFloatOrNull()
                        if (fx != null && fy != null)
                            onChange(macro.copy(actions = macro.actions + MacroAction.Tap(fx, fy, (ms.toLongOrNull() ?: 30L).coerceAtLeast(1))))
                    }) { Text("Toque (X,Y, segura ms)") }
                    Button(onClick = {
                        onChange(macro.copy(actions = macro.actions + MacroAction.Wait((ms.toLongOrNull() ?: 100L).coerceAtLeast(1))))
                    }) { Text("Esperar ms") }
                }
                Button(onClick = {
                    val fx = x.toFloatOrNull(); val fy = y.toFloatOrNull()
                    if (fx != null && fy != null)
                        onChange(
                            macro.copy(
                                loop = true,
                                actions = macro.actions + MacroAction.Tap(fx, fy, 30L) +
                                    MacroAction.Wait((ms.toLongOrNull() ?: 100L).coerceAtLeast(1))
                            )
                        )
                }) { Text("Clique repetido em X,Y (a cada ms)") }
            }
        }
        itemsIndexedActions(macro, onChange)
    }
}

private fun Macro.scaled(factor: Double): Macro = copy(actions = actions.map {
    when (it) {
        is MacroAction.Tap -> it.copy(holdMs = (it.holdMs * factor).toLong().coerceIn(1L, 3_600_000L))
        is MacroAction.Wait -> it.copy(ms = (it.ms * factor).toLong().coerceIn(1L, 3_600_000L))
    }
})

@Composable
private fun NumField(value: Long, label: String, modifier: Modifier, onValue: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { t ->
            val digits = t.filter(Char::isDigit).take(6)
            text = digits
            onValue(digits.toLongOrNull() ?: 0L)
        },
        label = { Text(label) }, singleLine = true, modifier = modifier
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedActions(macro: Macro, onChange: (Macro) -> Unit) {
    items(macro.actions.size) { i ->
        val a = macro.actions[i]
        fun replace(n: MacroAction) =
            onChange(macro.copy(actions = macro.actions.mapIndexed { j, old -> if (j == i) n else old }))
        GlassCard {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${i + 1}. " + if (a is MacroAction.Tap) "Toque" else "Esperar", Modifier.weight(1f))
                IconButton(onClick = { onChange(macro.copy(actions = macro.actions.filterIndexed { j, _ -> j != i })) }) {
                    Icon(Icons.Rounded.Delete, "Remover ação")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (a) {
                    is MacroAction.Tap -> {
                        NumField(a.x.toLong(), "X", Modifier.weight(1f)) { replace(a.copy(x = it.toFloat())) }
                        NumField(a.y.toLong(), "Y", Modifier.weight(1f)) { replace(a.copy(y = it.toFloat())) }
                        NumField(a.holdMs, "segura ms", Modifier.weight(1f)) { replace(a.copy(holdMs = it.coerceAtLeast(1L))) }
                    }
                    is MacroAction.Wait ->
                        NumField(a.ms, "ms", Modifier.weight(1f)) { replace(a.copy(ms = it.coerceAtLeast(1L))) }
                }
            }
        }
    }
}
