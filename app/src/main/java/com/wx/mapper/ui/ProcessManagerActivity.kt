package com.wx.mapper.ui

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
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
 * Gerenciador de processos: lista os apps em execução (do maior uso de RAM para o menor) e deixa
 * PARAR ou CONGELAR os que você escolher, para sobrar CPU/RAM para o jogo.
 *
 *  - Parar    = `am force-stop` (o app pode reabrir sozinho depois).
 *  - Congelar = `pm suspend` (o app não roda nem em segundo plano até você DESCONGELAR). Reversível.
 *
 * Só aparecem apps instalados (e atualizações de apps do sistema). Launcher, teclado, SystemUI,
 * Serviços do Google, Shizuku, serviços de acessibilidade e o próprio WX nunca aparecem.
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

private data class RunningProcess(
    val pid: Int,
    val uid: String,
    val name: String,
    val label: String,
    val packageName: String?,
    val rssMb: Int,
    val manageable: Boolean
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
 * Lista processos do Android via Shizuku.
 * Exclui somente tarefas do kernel (nomes entre colchetes, como [kworker/...]).
 * Processos do sistema/serviços são exibidos, mas ficam somente para leitura.
 */
private fun listRunningProcesses(ctx: Context, frozen: Set<String>): List<RunningProcess> {
    val result = ShizukuShell.exec("ps -A -o USER,PID,PPID,RSS,NAME")
    if (result.out.isBlank()) return emptyList()

    val blocked = FreezeStore.protectedPackages(ctx)
    val pm = ctx.packageManager
    val rows = mutableListOf<RunningProcess>()

    result.out.lineSequence().drop(1).forEach { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 5)
        if (parts.size < 5) return@forEach

        val user = parts[0]
        val pid = parts[1].toIntOrNull() ?: return@forEach
        val parentPid = parts[2].toIntOrNull() ?: -1
        val rssKb = parts[3].toIntOrNull() ?: 0
        val processName = parts[4].trim()
        if (processName.isBlank()) return@forEach

        // Tarefas do kernel: nome entre colchetes ou processo filho do kthreadd (PID 2).
        if ((processName.startsWith("[") && processName.endsWith("]")) || parentPid == 2) return@forEach

        val baseName = processName.substringBefore(':')
        val directPackage = if (ShizukuShell.isSafePackageName(baseName)) baseName else null
        val uid = when {
            user.matches(Regex("^u\\d+_a\\d+$")) -> {
                val match = Regex("^u(\\d+)_a(\\d+)$").matchEntire(user)
                if (match != null) {
                    (match.groupValues[1].toInt() * 100000 + 10000 +
                        match.groupValues[2].toInt()).toString()
                } else user
            }
            else -> user
        }

        val packageName = directPackage?.takeIf {
            try { pm.getApplicationInfo(it, 0); true } catch (_: Exception) { false }
        } ?: uid.toIntOrNull()?.let { numericUid ->
            pm.getPackagesForUid(numericUid)?.firstOrNull { pkg ->
                processName == pkg || processName.startsWith("$pkg:")
            }
        }

        val info = packageName?.let {
            try { pm.getApplicationInfo(it, 0) } catch (_: Exception) { null }
        }
        val label = if (info != null) {
            pm.getApplicationLabel(info).toString()
        } else {
            processName
        }

        // Só apps comuns/atualizados podem ser selecionados para as ações existentes.
        // Daemons e componentes essenciais continuam visíveis, mas sem checkbox.
        val isSystem = info?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        val updatedSystem = info?.flags?.and(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val manageable = packageName != null &&
            info != null &&
            (!isSystem || updatedSystem) &&
            packageName !in blocked &&
            packageName !in frozen &&
            ShizukuShell.isSafePackageName(packageName)

        rows += RunningProcess(
            pid = pid,
            uid = user,
            name = processName,
            label = label,
            packageName = packageName,
            rssMb = rssKb / 1024,
            manageable = manageable
        )
    }

    return rows.sortedWith(compareByDescending<RunningProcess> { it.rssMb }.thenBy { it.name })
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

    var processes by remember { mutableStateOf<List<RunningProcess>>(emptyList()) }
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
                val list = withContext(Dispatchers.IO) { listRunningProcesses(ctx, loadFrozen(ctx)) }
                processes = list
                selected = selected.filter { pkg -> list.any { it.packageName == pkg && it.manageable } }.toSet()
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
                    if (busy && processes.isEmpty()) "Carregando…" else "Processos em execução (${processes.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(processes, key = { it.pid }) { process ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (process.manageable && process.packageName != null) {
                        Checkbox(
                            checked = process.packageName in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + process.packageName
                                    else selected - process.packageName
                            }
                        )
                    } else {
                        Text("•", modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(process.label, maxLines = 1)
                        Text(
                            "${process.name} • PID ${process.pid} • UID ${process.uid}" +
                                (if (process.manageable) "" else " • somente leitura"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            maxLines = 2
                        )
                    }
                    Text("${process.rssMb} MB", style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
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
