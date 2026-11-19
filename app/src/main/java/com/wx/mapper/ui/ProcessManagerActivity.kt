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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.wx.mapper.ui.theme.WxTheme
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

private const val PREFS = "wx_frozen"
private const val KEY_FROZEN = "frozen"

private fun loadFrozen(ctx: Context): Set<String> =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FROZEN, emptySet()) ?: emptySet()

private fun saveFrozen(ctx: Context, set: Set<String>) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FROZEN, set.toSet()).apply()
}

private fun appLabel(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) {
    pkg
}

/** Pacotes que NUNCA devem aparecer na lista (quebrariam o aparelho ou o próprio WX). */
private fun protectedPackages(ctx: Context): Set<String> {
    val set = mutableSetOf(
        ctx.packageName,
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.media",
        "moe.shizuku.privileged.api",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.webview",
        "com.android.webview"
    )
    try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName?.let { set += it }
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { set += it }
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.forEach { service -> set += service.substringBefore('/') }
    } catch (_: Exception) {
    }
    return set
}

/** Lê `ps` com o Shizuku e agrupa por app. Devolve só apps que podem ser parados/congelados. */
private fun listRunningApps(ctx: Context, frozen: Set<String>): List<RunningApp> {
    val result = ShizukuShell.exec("ps -A -o USER,PID,RSS,NAME")
    if (!result.ok && result.out.isBlank()) return emptyList()
    val blocked = protectedPackages(ctx)
    val pm = ctx.packageManager
    val userRow = Regex("^u\\d+_a\\d+$")

    val rssByPkg = HashMap<String, Int>()
    val countByPkg = HashMap<String, Int>()
    result.out.lineSequence().forEach { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 4 || !userRow.matches(parts[0])) return@forEach
        val rssKb = parts[2].toIntOrNull() ?: return@forEach
        val pkg = parts[3].substringBefore(':')
        if (!ShizukuShell.isSafePackageName(pkg) || pkg in blocked || pkg in frozen) return@forEach
        rssByPkg[pkg] = (rssByPkg[pkg] ?: 0) + rssKb
        countByPkg[pkg] = (countByPkg[pkg] ?: 0) + 1
    }

    return rssByPkg.mapNotNull { (pkg, rssKb) ->
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
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
