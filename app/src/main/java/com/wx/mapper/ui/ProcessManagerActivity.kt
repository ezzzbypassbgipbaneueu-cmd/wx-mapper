package com.wx.mapper.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.wx.mapper.util.PsParser
import com.wx.mapper.util.PsRow
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

private data class RunningApp(val pkg: String, val label: String, val rssMb: Int, val processes: Int)

private fun loadFrozen(ctx: Context): Set<String> = FreezeStore.frozen(ctx)

private fun saveFrozen(ctx: Context, set: Set<String>) = FreezeStore.setFrozen(ctx, set)

private fun appLabel(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) {
    pkg
}

private data class ListResult(val apps: List<RunningApp>, val problem: String?)

/** Do mais completo ao mais simples: cada aparelho/Android aceita um formato diferente do `ps`. */
private val PS_COMMANDS = listOf(
    "ps -A -o USER:16,PID,RSS,NAME:200",
    "ps -A -o USER,PID,RSS,NAME",
    "ps -A",
    "ps"
)

/**
 * Lê `ps` com o Shizuku e agrupa por app. Devolve só apps que podem ser parados/congelados.
 * Se algo der errado, [ListResult.problem] explica o quê (aparece na tela).
 */
private fun listRunningApps(ctx: Context, frozen: Set<String>): ListResult {
    var rows: List<PsRow> = emptyList()
    var lastProblem = ""
    for (cmd in PS_COMMANDS) {
        val r = ShizukuShell.exec(cmd)
        val parsed = PsParser.parse(r.out)
        if (parsed.isNotEmpty()) {
            rows = parsed
            break
        }
        lastProblem = if (!r.ok) {
            "\"$cmd\" falhou (código ${r.exitCode}): ${r.err.trim().take(160)}"
        } else {
            "\"$cmd\" rodou, mas a saída não tinha apps: ${r.out.trim().take(120)}"
        }
    }
    if (rows.isEmpty()) {
        return ListResult(emptyList(), lastProblem.ifBlank { "O comando ps não devolveu nada." })
    }

    val blocked = FreezeStore.protectedPackages(ctx)
    val pm = ctx.packageManager

    val rssByPkg = HashMap<String, Int>()
    val countByPkg = HashMap<String, Int>()
    rows.forEach { row ->
        val pkg = row.name.substringBefore(':')
        if (!ShizukuShell.isSafePackageName(pkg) || pkg in blocked || pkg in frozen) return@forEach
        rssByPkg[pkg] = (rssByPkg[pkg] ?: 0) + row.rssKb
        countByPkg[pkg] = (countByPkg[pkg] ?: 0) + 1
    }

    val apps = rssByPkg.mapNotNull { (pkg, rssKb) ->
        val info = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Exception) {
            return@mapNotNull null
        }
        val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        if (isSystem && !updatedSystem) return@mapNotNull null
        RunningApp(pkg, pm.getApplicationLabel(info).toString(), rssKb / 1024, countByPkg[pkg] ?: 1)
    }.sortedByDescending { it.rssMb }

    val problem = if (apps.isEmpty()) {
        "Li ${rows.size} processos de apps, mas nenhum pode ser parado/congelado " +
            "(só sobraram apps do sistema, protegidos ou já congelados)."
    } else null
    return ListResult(apps, problem)
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
    var problem by remember { mutableStateOf<String?>(null) }
    var autoEnabled by remember { mutableStateOf(FreezeStore.autoEnabled(ctx)) }
    var autoList by remember { mutableStateOf(FreezeStore.autoList(ctx)) }
    val detectorOn by TypingState.serviceConnected.collectAsState()

    fun refresh() {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { ShizukuShell.isAvailable() }
            available = ok
            if (ok) {
                val res = withContext(Dispatchers.IO) { listRunningApps(ctx, loadFrozen(ctx)) }
                apps = res.apps
                problem = res.problem
                selected = selected.filter { pkg -> res.apps.any { it.pkg == pkg } }.toSet()
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

    // Surface define a cor do texto (sem ela o texto fica preto sobre fundo escuro = tela "em branco").
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
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
        problem?.let {
            Text(
                "Não consegui listar os apps: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

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
            items(apps, key = { it.pkg }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = app.pkg in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + app.pkg else selected - app.pkg
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, maxLines = 1)
                        Text(
                            "${app.pkg} • ${app.processes} processo(s)",
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
}
