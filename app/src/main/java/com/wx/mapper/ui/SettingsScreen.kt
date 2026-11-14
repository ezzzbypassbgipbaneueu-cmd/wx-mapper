package com.wx.mapper.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.wx.mapper.model.CursorStyle
import com.wx.mapper.service.TypingState
import com.wx.mapper.util.CursorImages
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import com.wx.mapper.model.AppConfig
import com.wx.mapper.model.RecordingHideMode
import com.wx.mapper.model.ThemePreference
import com.wx.mapper.model.TouchMode
import com.wx.mapper.util.capitalizeFirst
import com.wx.mapper.util.keyCodeToString
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    appConfig: AppConfig = AppConfig.Default,
    onSave: (AppConfig) -> Unit = {},
    /** Aplica e SALVA na hora só as mudanças do cursor, sem precisar apertar "Save". */
    onApplyCursor: ((AppConfig) -> AppConfig) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
    onExportBackup: (Uri) -> Unit = {},
    onImportBackup: (Uri) -> Unit = {}
) {
    var newConfig by remember { mutableStateOf(appConfig) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(text = "Settings")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = "Back"
                        )
                    }
                }, actions = {
                    Button(modifier = Modifier.padding(end = 8.dp), enabled = newConfig != appConfig, onClick = {
                        onSave(newConfig)
                    }) {
                        Text("Save")
                    }
                })
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Box(
            Modifier
                .padding(innerPadding)
                .padding(8.dp)
                .verticalScroll(state = scrollState)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Key Configuration",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row {
                            KeyConfigTextField(title = "Toggle Shooting Mode:", value = newConfig.shootingModeKeyCode.keyCodeToString()) {
                                newConfig=newConfig.copy(shootingModeKeyCode = it.nativeKeyEvent.keyCode)
                                return@KeyConfigTextField true
                            }
                        }
                        HorizontalDivider()
                        KeyConfigTextField(title = "Fire:", value = newConfig.fireKeyCode.keyCodeToString()) {
                            newConfig=newConfig.copy(fireKeyCode = it.nativeKeyEvent.keyCode)
                            return@KeyConfigTextField true
                        }
                        HorizontalDivider()
                        KeyConfigTextField(title = "Scope:", value = newConfig.scopeKeyCode.keyCodeToString()) {
                            newConfig=newConfig.copy(scopeKeyCode = it.nativeKeyEvent.keyCode)
                            return@KeyConfigTextField true
                        }
                    }
                }
                Card {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Button Size",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("${(newConfig.buttonScale * 100).roundToInt()} %")
                        }
                        Slider(value = newConfig.buttonScale,
                            valueRange = 0f..2f, steps = 0, onValueChange = {
                                newConfig = newConfig.copy(buttonScale = it)
                            })
                    }
                }
                Card {
                    val context = LocalContext.current
                    var images by remember { mutableStateOf(CursorImages.list(context)) }

                    // aplica na tela e já salva no app
                    fun applyCursor(transform: (AppConfig) -> AppConfig) {
                        newConfig = transform(newConfig)
                        onApplyCursor(transform)
                    }

                    val imagePicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) {
                            val file = CursorImages.import(context, uri)
                            if (file != null) {
                                images = CursorImages.list(context)
                                applyCursor {
                                    it.copy(
                                        cursorImagePath = file.absolutePath,
                                        cursorImageVersion = System.currentTimeMillis(),
                                        cursorStyle = CursorStyle.IMAGE
                                    )
                                }
                            } else {
                                Toast.makeText(context, "Não foi possível ler essa imagem.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Cursor personalizado",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Adicione quantos PNGs quiser (fundo transparente fica melhor). As imagens ficam salvas no app; toque em uma para usar como cursor.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = { imagePicker.launch("image/*") }) {
                            Text("Adicionar PNG / imagem")
                        }
                        if (images.isEmpty()) {
                            Text(
                                "Nenhuma imagem salva ainda.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                images.forEach { file ->
                                    val thumb = remember(file.path, file.lastModified()) {
                                        runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
                                    }
                                    val selected = newConfig.cursorImagePath == file.absolutePath
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Gray.copy(alpha = 0.3f))
                                            .border(
                                                width = if (selected) 3.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                applyCursor {
                                                    it.copy(
                                                        cursorImagePath = file.absolutePath,
                                                        cursorImageVersion = System.currentTimeMillis(),
                                                        cursorStyle = CursorStyle.IMAGE
                                                    )
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb,
                                                contentDescription = "Cursor",
                                                modifier = Modifier
                                                    .size(52.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val current = newConfig.cursorImagePath
                        if (current != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Usar a imagem como cursor", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = newConfig.cursorStyle == CursorStyle.IMAGE,
                                    onCheckedChange = { on ->
                                        applyCursor {
                                            it.copy(cursorStyle = if (on) CursorStyle.IMAGE else CursorStyle.ARROW)
                                        }
                                    })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Ponto do clique no centro", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Desligado: o clique sai da ponta (canto superior esquerdo) da imagem.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = newConfig.cursorImageHotspotCenter,
                                    onCheckedChange = { on ->
                                        applyCursor { it.copy(cursorImageHotspotCenter = on) }
                                    })
                            }
                            TextButton(onClick = {
                                File(current).let { CursorImages.delete(it) }
                                images = CursorImages.list(context)
                                applyCursor {
                                    it.copy(
                                        cursorImagePath = null,
                                        cursorStyle = if (it.cursorStyle == CursorStyle.IMAGE)
                                            CursorStyle.ARROW else it.cursorStyle
                                    )
                                }
                            }) { Text("Excluir a imagem selecionada") }
                        }
                    }
                }
                Card {
                    val context = LocalContext.current
                    val detectorOn by TypingState.serviceConnected.collectAsState()
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Modo digitar",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Automático", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Ao abrir um campo de texto, o teclado é liberado sozinho (sem clicar em nada). Ao tocar fora do campo, volta ao mapeamento.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = newConfig.autoTypingMode,
                                onCheckedChange = { newConfig = newConfig.copy(autoTypingMode = it) })
                        }
                        if (newConfig.autoTypingMode && !detectorOn) {
                            Text(
                                "Para detectar o campo de texto, ative \"WX Mapper - detector de digitação\" em Acessibilidade. Ele só observa o foco; não lê o que você digita.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            FilledTonalButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) { Text("Abrir Acessibilidade") }
                        } else if (newConfig.autoTypingMode) {
                            Text(
                                "Detector ativo.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider()
                        KeyConfigTextField(
                            title = "Ligar modo digitar:",
                            value = newConfig.typingModeKeyCode.keyCodeToString()
                        ) {
                            newConfig = newConfig.copy(typingModeKeyCode = it.nativeKeyEvent.keyCode)
                            return@KeyConfigTextField true
                        }
                        Text(
                            "Para sair do modo digitar manual: toque (ou segure) na bolha.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Card {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Modo gravação",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Esconde os botões e a bolha do keymapper para gravar a tela (o cursor continua visível para você). O mapeamento continua funcionando. Aperte a tecla de novo (ou use a ação na notificação) para mostrar.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        KeyConfigTextField(
                            title = "Esconder / mostrar tudo:",
                            value = newConfig.recordingModeKeyCode.keyCodeToString()
                        ) {
                            newConfig = newConfig.copy(recordingModeKeyCode = it.nativeKeyEvent.keyCode)
                            return@KeyConfigTextField true
                        }
                        Text("Como esconder", style = MaterialTheme.typography.titleMedium)
                        listOf(
                            RecordingHideMode.BUTTONS_ONLY to "Só botões e bolha (cursor continua na gravação)",
                            RecordingHideMode.SKIP_SCREENSHOT to "Tudo visível pra mim, fora da gravação (SKIP_SCREENSHOT, experimental)",
                            RecordingHideMode.FLAG_SECURE to "Tudo visível pra mim, fora da gravação (FLAG_SECURE, pode gravar preto)",
                        ).forEach { (mode, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { newConfig = newConfig.copy(recordingHideMode = mode) }
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = newConfig.recordingHideMode == mode,
                                    onClick = { newConfig = newConfig.copy(recordingHideMode = mode) }
                                )
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(
                            "SKIP_SCREENSHOT usa APIs ocultas do Android e pode não funcionar em todos os aparelhos ou gravadores; se falhar, o app esconde só os botões.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(checked = newConfig.deleteDataOnRemove, onCheckedChange = {
                            newConfig = newConfig.copy(deleteDataOnRemove = it)
                        })
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    var cancellableDropDownExpanded by remember { mutableStateOf(false) }
                    var scopeDropDownExpanded by remember { mutableStateOf(false) }

                    ConstraintLayout(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        val (title,
                            cancelTitle,
                            cancelBody,
                            cancelSelector,
                            divider1,
                            scopeTitle,
                            scopeBody,
                            scopeSelector) = createRefs()
                        Text(
                            "Touch Mode",
                            modifier = Modifier.constrainAs(title) {
                                top.linkTo(parent.top)
                                start.linkTo(parent.start)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Cancelable Button",
                            modifier = Modifier
                                .constrainAs(cancelTitle) {
                                    top.linkTo(title.bottom)
                                }
                                .padding(top = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            modifier = Modifier
                                .constrainAs(cancelBody) {
                                    top.linkTo(cancelTitle.bottom)
                                }
                                .padding(top = 8.dp),
                            text = "Touch behaviour for cancelable buttons",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedCard(modifier = Modifier.constrainAs(cancelSelector) {
                            bottom.linkTo(cancelBody.bottom)
                            top.linkTo(cancelTitle.top)
                            end.linkTo(parent.end)
                        }, onClick = { cancellableDropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.cancellableTouchMode.name.capitalizeFirst(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = cancellableDropDownExpanded,
                                onDismissRequest = { cancellableDropDownExpanded = false }) {
                                TouchMode.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.name.capitalizeFirst()) },
                                        onClick = {
                                            newConfig = newConfig.copy(cancellableTouchMode = it)
                                            cancellableDropDownExpanded = false
                                        })
                                }
                            }
                        }
                        HorizontalDivider(
                            Modifier
                                .constrainAs(divider1) {
                                    top.linkTo(cancelBody.bottom)
                                }
                                .padding(top = 8.dp))
                        Text(
                            "Scope",
                            modifier = Modifier
                                .constrainAs(scopeTitle) {
                                    top.linkTo(divider1.bottom)
                                }
                                .padding(top = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            modifier = Modifier
                                .constrainAs(scopeBody) {
                                    top.linkTo(scopeTitle.bottom)
                                }
                                .padding(top = 8.dp),
                            text = "Touch behaviour for Scope button",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedCard(modifier = Modifier.constrainAs(scopeSelector) {
                            bottom.linkTo(scopeBody.bottom)
                            top.linkTo(scopeTitle.top)
                            end.linkTo(parent.end)
                        }, onClick = { scopeDropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.normalBtnTouchMode.name.capitalizeFirst(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = scopeDropDownExpanded,
                                onDismissRequest = { scopeDropDownExpanded = false }) {
                                TouchMode.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.name.capitalizeFirst()) },
                                        onClick = {
                                            newConfig = newConfig.copy(normalBtnTouchMode = it)
                                            scopeDropDownExpanded = false
                                        })
                                }
                            }
                        }
                    }


                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    var dropDownExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                "Theme",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Text(
                                modifier = Modifier.padding(top = 8.dp),
                                text = "Set theme preference for this app",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedCard(onClick = { dropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.themePreference.name.capitalizeFirst(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = dropDownExpanded,
                                onDismissRequest = { dropDownExpanded = false }) {
                                ThemePreference.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.name.capitalizeFirst()) },
                                        onClick = {
                                            newConfig = newConfig.copy(themePreference = it)
                                            dropDownExpanded = false
                                        })
                                }
                            }
                        }

                    }
                }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri -> uri?.let(onExportBackup) }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(onImportBackup) }
                Card {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Backup das configurações", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Exporta tudo (botões de cada jogo, macros, autoclicker, cursor…) para um arquivo .json. Importe esse arquivo em outro aparelho para restaurar.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { exportLauncher.launch("wx-mapper-config.json") }) { Text("Exportar") }
                            FilledTonalButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Importar") }
                        }
                    }
                }
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    newConfig = AppConfig.Default
                }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text(
                            modifier = Modifier.padding(start = 16.dp),
                            text = "Reset To Defaults",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onNavigateAbout) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null)
                        Text(
                            modifier = Modifier.padding(start = 16.dp),
                            text = "About",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun KeyConfigTextField(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    onKeyEvent: (KeyEvent) -> Boolean
) {
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    Row {
        Text(
            modifier = modifier
                .fillMaxWidth()
                .weight(1f),
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedCard(border = BorderStroke(
            1.dp,
            color = if (hasFocus)   Color.Cyan else MaterialTheme.colorScheme.outline
        ),
            modifier = Modifier.clickable { focusRequester.requestFocus() }) {
            Text(
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 16.dp)
                    .focusRequester(focusRequester)
                    .onKeyEvent { onKeyEvent(it) }
                    .onFocusChanged { hasFocus = it.isFocused }
                    .focusable(),
                text = value,
                style = TextStyle(color = LocalContentColor.current),
            )
        }
    }
}
