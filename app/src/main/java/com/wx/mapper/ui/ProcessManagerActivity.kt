package com.wx.mapper.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wx.mapper.data.external.ShizukuShell
import com.wx.mapper.service.TypingState
import com.wx.mapper.ui.theme.WxTheme
import com.wx.mapper.util.FreezeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Gerenciador de processos: lista processos de apps e do sistema (do maior uso de RAM para o menor).
 * Apenas apps comuns instalados podem ser selecionados para PARAR ou CONGELAR.
 *
 *  - Parar    = `am force-stop` (o app pode reabrir sozinho depois).
 *  - Congelar = `pm suspend` (o app não roda nem em segundo plano até você DESCONGELAR). Reversível.
 *
 * Processos do sistema e serviços auxiliares também aparecem, mas ficam protegidos contra congelamento.
 */
class ProcessManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WxTheme {
                ProcessManagerScreen()
            }
        }
    }
}

private data class RunningApp(
    val id: String,
    val pkg: String?,
    val label: String,
    val rssMb: Int,
    val processes: Int,
    val canFreeze: Boolean
)

private fun loadFrozen(ctx: Context): Set<String> = FreezeStore.frozen(ctx)

private fun saveFrozen(ctx: Context, set: Set<String>) = FreezeStore.setFrozen(ctx, set)

private fun appLabel(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) {
    pkg
}

/**
 * Lê TODOS os processos visíveis ao Shizuku. Processos que não correspondem a um
 * pacote instalado também aparecem, mas ficam somente para visualização.
 * O congelamento continua sendo feito por pacote (pm suspend), nunca por PID.
 */
private fun listRunningApps(ctx: Context, frozen: Set<String>): List<RunningApp> {
    val result = ShizukuShell.exec("ps -A -o USER,PID,RSS,NAME")
    if (!result.ok && result.out.isBlank()) return emptyList()

    val blocked = FreezeStore.protectedPackages(ctx)
    val pm = ctx.packageManager
    data class Aggregate(
        val pkg: String?,
        val label: String,
        var rssKb: Long,
        var count: Int,
        val canFreeze: Boolean
    )
    val grouped = LinkedHashMap<String, Aggregate>()

    result.out.lineSequence().drop(1).forEach { line ->
        // NAME fica no último campo e pode conter ':' (processos auxiliares do app).
        val parts = line.trim().split(Regex("\\s+"), limit = 4)
        if (parts.size < 4) return@forEach
        val rssKb = parts[2].toLongOrNull() ?: 0L
        val processName = parts[3].trim()
        if (processName.isBlank()) return@forEach

        val possiblePkg = processName.substringBefore(':')
        val pkg = possiblePkg.takeIf { ShizukuShell.isSafePackageName(it) }
        val appInfo = if (pkg != null) try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Exception) {
            null
        } else null

        // Só pacotes instalados podem ser congelados. Apps de sistema, pacotes
        // protegidos e itens já congelados continuam visíveis, mas não selecionáveis.
        val installedPkg = if (appInfo != null) pkg else null
        if (installedPkg != null && installedPkg in frozen) return@forEach

        val label = if (appInfo != null) {
            pm.getApplicationLabel(appInfo).toString()
        } else {
            processName
        }
        val isSystem = appInfo?.let {
            it.flags and ApplicationInfo.FLAG_SYSTEM != 0
        } ?: true
        val canFreeze = installedPkg != null && !isSystem && installedPkg !in blocked
        val key = installedPkg ?: "#process:$processName"
        val current = grouped[key]
        if (current == null) {
            grouped[key] = Aggregate(installedPkg, label, rssKb, 1, canFreeze)
        } else {
            current.rssKb += rssKb
            current.count += 1
        }
    }

    return grouped.map { (key, item) ->
        RunningApp(
            id = key,
            pkg = item.pkg,
            label = item.label,
            rssMb = (item.rssKb / 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            processes = item.count,
            canFreeze = item.canFreeze
        )
    }.sortedWith(compareByDescending<RunningApp> { it.rssMb }.thenBy { it.label.lowercase() })
}

private fun freeRamText(ctx: Context): String {
    val info = ActivityManager.MemoryInfo()
    (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return "RAM livre: ${info.availMem / 1048576} MB de ${info.totalMem / 1048576} MB"
}

@Composable
private fun ProcessManagerScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<RunningApp>>(emptyList()) }
    var frozen by remember { mutableStateOf(loadFrozen(ctx)) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ramText by remember { mutableStateOf(freeRamText(ctx)) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(true) }
    var autoEnabled by remember { mutableStateOf(FreezeStore.autoEnabled(ctx)) }
    var autoList by remember { mutableStateOf(FreezeStore.autoList(ctx)) }
    val detectorOn by TypingState.serviceConnected.collectAsState()

    fun refresh() {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { ShizukuShell.isAvailable() }
            available = ok
            if (ok) {
                val list = withContext(Dispatchers.IO) { listRunningApps(ctx, loadFrozen(ctx)) }
                apps = list
                selected = selected.filter { pkg -> list.any { it.pkg == pkg } }.toSet()
            }
            ramText = freeRamText(ctx)
            busy = false
        }
    }

    /** Roda o comando em cada pacote e devolve só os que deram certo. */
    fun runOn(packages: Collection<String>, template: String, onDone: (List<String>) -> Unit) {
        scope.launch {
            busy = true
            val succeeded = withContext(Dispatchers.IO) {
                packages.filter { pkg ->
                    ShizukuShell.isSafePackageName(pkg) && ShizukuShell.exec(template.replace("%p", pkg)).ok
                }
            }
            onDone(succeeded)
            busy = false
            refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Processos", style = MaterialTheme.typography.headlineSmall)
        Text(ramText, style = MaterialTheme.typography.bodyMedium)

        if (!available) {
            Text(
                "O Shizuku não está ativo ou o WX não tem permissão. Abra o Shizuku, inicie o serviço e conceda a permissão ao WX.",
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = { refresh() }) { Text("Tentar de novo") }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Congelar sozinho ao abrir o jogo", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (autoList.isEmpty()) "Lista automática vazia. Marque apps abaixo e toque em \"Adicionar à lista automática\"."
                    else "Lista automática: ${autoList.size} app(s). Congela ao abrir um jogo do WX e descongela 8 s depois que você sai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Switch(
                checked = autoEnabled,
                onCheckedChange = {
                    autoEnabled = it
                    FreezeStore.setAutoEnabled(ctx, it)
                }
            )
        }
        if (autoEnabled && !detectorOn) {
            Text(
                "Ative o serviço de acessibilidade do WX para ele detectar quando o jogo abre.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("Atualizar") }
            OutlinedButton(
                enabled = !busy && selected.isNotEmpty(),
                onClick = {
                    val targets = selected.toList()
                    runOn(targets, "am force-stop %p") { done ->
                        message = "${done.size} app(s) parado(s)."
                        selected = emptySet()
                    }
                }
            ) { Text("Parar") }
            Button(
                enabled = !busy && selected.isNotEmpty(),
                onClick = {
                    val targets = selected.toList()
                    runOn(targets, "pm suspend --user 0 %p") { done ->
                        // Só guarda como "congelado" o que realmente aceitou ser congelado.
                        val merged = loadFrozen(ctx) + done
                        saveFrozen(ctx, merged)
                        frozen = merged
                        message = if (done.size == targets.size) {
                            "${done.size} app(s) congelado(s)."
                        } else {
                            "${done.size} de ${targets.size} congelado(s). Alguns apps não aceitam ser congelados."
                        }
                        selected = emptySet()
                    }
                }
            ) { Text("Congelar") }
        }
        OutlinedButton(
            enabled = !busy && selected.isNotEmpty(),
            onClick = {
                val merged = FreezeStore.autoList(ctx) + selected
                FreezeStore.setAutoList(ctx, merged)
                autoList = merged
                message = "${selected.size} app(s) adicionado(s) à lista automática."
                selected = emptySet()
            }
        ) { Text("Adicionar à lista automática") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (autoList.isNotEmpty()) {
                item {
                    Text(
                        "Lista automática (${autoList.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(autoList.toList(), key = { "a_$it" }) { pkg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(appLabel(ctx, pkg), modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(
                            onClick = {
                                val rest = FreezeStore.autoList(ctx) - pkg
                                FreezeStore.setAutoList(ctx, rest)
                                autoList = rest
                            }
                        ) { Text("Remover") }
                    }
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            }
            if (frozen.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Congelados (${frozen.size})",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                runOn(frozen.toList(), "pm unsuspend --user 0 %p") { done ->
                                    val rest = loadFrozen(ctx) - done.toSet()
                                    saveFrozen(ctx, rest)
                                    frozen = rest
                                    message = if (rest.isEmpty()) "Tudo descongelado."
                                    else "${done.size} descongelado(s); ${rest.size} não deu certo."
                                }
                            }
                        ) { Text("Descongelar tudo") }
                    }
                }
                items(frozen.toList(), key = { "f_$it" }) { pkg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(appLabel(ctx, pkg), modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                runOn(listOf(pkg), "pm unsuspend --user 0 %p") { done ->
                                    if (done.isNotEmpty()) {
                                        val rest = loadFrozen(ctx) - pkg
                                        saveFrozen(ctx, rest)
                                        frozen = rest
                                        message = "${appLabel(ctx, pkg)} descongelado."
                                    } else {
                                        message = "Não consegui descongelar ${appLabel(ctx, pkg)}."
                                    }
                                }
                            }
                        ) { Text("Descongelar") }
                    }
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            }

            item {
                Text(
                    if (busy && apps.isEmpty()) "Carregando…" else "Em execução (${apps.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(apps, key = { it.id }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = app.pkg != null && app.pkg in selected,
                        enabled = app.canFreeze && app.pkg != null && !busy,
                        onCheckedChange = { checked ->
                            val pkg = app.pkg ?: return@Checkbox
                            selected = if (checked) selected + pkg else selected - pkg
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, maxLines = 1)
                        Text(
                            "${app.pkg ?: app.id.removePrefix("#process:")} • ${app.processes} processo(s)" +
                                if (app.canFreeze) "" else " • somente visualização",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1
                        )
                    }
                    Text("${app.rssMb} MB", style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                Text(
                    "Congelar pausa o app por completo (sem notificações) até você descongelar. " +
                        "Se o WX for desinstalado, descongele antes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
}
