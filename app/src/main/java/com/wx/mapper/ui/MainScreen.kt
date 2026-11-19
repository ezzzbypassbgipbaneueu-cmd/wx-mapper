package com.wx.mapper.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wx.mapper.MainActivityViewModel
import com.wx.mapper.model.GameSummary
import com.wx.mapper.ui.theme.GlassCard
import com.wx.mapper.ui.theme.WxAccent
import com.wx.mapper.ui.theme.WxBackground
import com.wx.mapper.ui.theme.WxPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val WxGreen = Color(0xFF22C55E)
private val WxAmber = Color(0xFFF59E0B)

/** Pílula de status: bolinha colorida + texto. */
@Composable
private fun StatusChip(text: String, ok: Boolean, onClick: (() -> Unit)? = null) {
    val color = if (ok) WxGreen else WxAmber
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

private fun summaryText(summary: GameSummary?): String {
    if (summary == null) return "Perfil novo · sem keymap ainda"
    val parts = mutableListOf<String>()
    parts += if (summary.buttons == 0) "sem botões" else "${summary.buttons} botões"
    if (summary.macros > 0) parts += "${summary.macros} macros"
    if (summary.autoClickerEnabled) parts += "${summary.cps.roundToInt()} CPS"
    return parts.joinToString(" · ")
}

/** Atalho para uma ferramenta do WX (treino de mira, processos). O fundo da tela é sempre escuro. */
@Composable
private fun ToolCard(title: String, subtitle: String, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Spacer(Modifier.size(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f)
        )
    }
}

/** Cartão de um jogo: ícone, nome, resumo do perfil, botão Jogar e menu. */
@Composable
private fun GameCard(
    viewModel: MainActivityViewModel,
    packageName: String,
    summary: GameSummary?,
    onPlay: () -> Unit,
    onOpenProfile: () -> Unit,
    onRemove: () -> Unit,
) {
    val icon by produceState<Drawable?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { viewModel.getPackageIcon(packageName) }
    }
    val label = remember(packageName) { viewModel.getPackageLabel(packageName) }
    var menuOpen by remember { mutableStateOf(false) }

    GlassCard(modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onOpenProfile)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = icon,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    summaryText(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Mais opções")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Abrir perfil") },
                        onClick = { menuOpen = false; onOpenProfile() }
                    )
                    DropdownMenuItem(
                        text = { Text("Remover da lista") },
                        onClick = { menuOpen = false; onRemove() }
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        Button(
            onClick = onPlay,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = WxPurple, contentColor = Color.White)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Jogar com este perfil")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    appName: String,
    viewModel: MainActivityViewModel,
    snackbarHostState: SnackbarHostState,
    isShizukuReady: Boolean,
    isOverlayRunning: Boolean,
    onNavigateToSettings: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onPackageIconClick: (String) -> Unit
) {
    val appContext = LocalContext.current
    val packages by viewModel.addedPackages.collectAsState()
    val summaries by viewModel.summaries.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isBottomSheetVisible by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var packageToRemove by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = modifier.background(WxBackground),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { isBottomSheetVisible = true },
                containerColor = WxPurple,
                contentColor = Color.White,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Adicionar jogo") }
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            appName,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                brush = WxAccent,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            "Cada jogo com o seu keymap",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Configurações")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(if (isShizukuReady) "Shizuku ativo" else "Shizuku desligado", isShizukuReady)
                    StatusChip(if (isOverlayRunning) "Overlay ligado" else "Overlay desligado", isOverlayRunning)
                    StatusChip(
                        "${connectedDevices.size} dispositivo(s)",
                        connectedDevices.isNotEmpty(),
                        onClick = { showDevices = !showDevices }
                    )
                }
            }
            item {
                AnimatedVisibility(showDevices) {
                    GlassCard {
                        if (connectedDevices.isEmpty()) {
                            Text("Nenhum teclado ou mouse externo conectado.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            connectedDevices.values.forEach { name ->
                                Text("• $name", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Ferramentas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            item {
                ToolCard(
                    title = "Treino de mira",
                    subtitle = "Bolinha em movimento para treinar com o cursor do WX, mouse ou toque.",
                    onClick = { appContext.startActivity(Intent(appContext, AimTrainerActivity::class.java)) }
                )
            }
            item {
                ToolCard(
                    title = "Processos",
                    subtitle = "Veja os apps em execução e pare ou congele os que atrapalham o jogo.",
                    onClick = { appContext.startActivity(Intent(appContext, ProcessManagerActivity::class.java)) }
                )
            }
            item {
                Text(
                    "Seus jogos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (packages.isEmpty()) {
                item {
                    GlassCard {
                        Text("Nenhum jogo ainda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Adicione um jogo para criar o perfil dele. Cada jogo guarda o próprio keymap, macros e autoclicker, sem misturar com os outros.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.size(12.dp))
                        Button(
                            onClick = { isBottomSheetVisible = true },
                            colors = ButtonDefaults.buttonColors(containerColor = WxPurple, contentColor = Color.White)
                        ) { Text("Adicionar jogo") }
                    }
                }
            } else {
                items(packages, key = { it }) { pkg ->
                    GameCard(
                        viewModel = viewModel,
                        packageName = pkg,
                        summary = summaries[pkg],
                        onPlay = { onPackageIconClick(pkg) },
                        onOpenProfile = { onOpenProfile(pkg) },
                        onRemove = { packageToRemove = pkg }
                    )
                }
            }
        }

        packageToRemove?.let { pkg ->
            val deletes = viewModel.appConfig.value.deleteDataOnRemove
            AlertDialog(
                onDismissRequest = { packageToRemove = null },
                title = { Text("Remover ${viewModel.getPackageLabel(pkg)}?") },
                text = {
                    Text(
                        if (deletes) "O keymap, as macros e o autoclicker deste jogo serão apagados."
                        else "O perfil fica guardado: se adicionar o jogo de novo, tudo volta como estava."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removePackage(pkg)
                        packageToRemove = null
                    }) { Text("Remover") }
                },
                dismissButton = { TextButton(onClick = { packageToRemove = null }) { Text("Cancelar") } }
            )
        }

        if (isBottomSheetVisible) {
            val context = LocalContext.current
            ModalBottomSheet(
                onDismissRequest = { isBottomSheetVisible = false },
                sheetState = sheetState
            ) {
                var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
                var query by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    apps = withContext(Dispatchers.IO) {
                        viewModel.getInstalledPackages()
                            .map { it.packageName }
                            .filter { it != context.packageName && it !in packages }
                            .map { it to viewModel.getPackageLabel(it) }
                            .sortedBy { it.second.lowercase() }
                    }
                }
                Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
                    Text("Adicionar jogo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Buscar app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(8.dp))
                    val list = apps
                    if (list == null) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val filtered = list.filter { it.second.contains(query.trim(), ignoreCase = true) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(filtered, key = { it.first }) { (pkg, label) ->
                                val icon by produceState<Drawable?>(initialValue = null, pkg) {
                                    value = withContext(Dispatchers.IO) { viewModel.getPackageIcon(pkg) }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            viewModel.addPackage(pkg)
                                            isBottomSheetVisible = false
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            pkg,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
