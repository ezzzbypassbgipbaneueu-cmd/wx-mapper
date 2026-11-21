package com.wx.mapper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wx.mapper.music.MusicPlayer
import com.wx.mapper.ui.theme.WxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Player de música: cada subpasta de /storage/emulated/0/msc/ é uma playlist. */
class MusicActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MusicPlayer.init(this)
        setContent {
            WxTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MusicScreen()
                }
            }
        }
    }
}

@Composable
private fun MusicScreen() {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(MusicPlayer.hasAccess(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = it }
    val lib by MusicPlayer.library.collectAsState()
    val st by MusicPlayer.state.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    suspend fun rescan() {
        loading = true
        withContext(Dispatchers.IO) { MusicPlayer.scan() }
        loading = false
    }
    LaunchedEffect(access) { if (access && !MusicPlayer.scanned) rescan() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Text("Música", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pasta: ${MusicPlayer.ROOT.path} — cada subpasta é uma playlist.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        if (!access) {
            Text("Preciso de permissão para ler os áudios.", modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = { launcher.launch(MusicPlayer.permission()) }) { Text("Permitir") }
            return@Column
        }

        val scope = androidx.compose.runtime.rememberCoroutineScope()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { scope.launch { rescan() } }, enabled = !loading) {
                Text(if (loading) "Lendo…" else "Atualizar pastas")
            }
            if (open != null) TextButton(onClick = { open = null }) { Text("← Playlists") }
        }

        Column(modifier = Modifier.weight(1f)) {
            val current = open
            if (current == null) {
                if (lib.isEmpty()) {
                    Text(
                        if (loading) "Procurando músicas…"
                        else "Nenhuma música encontrada. Crie subpastas dentro de /storage/emulated/0/msc/ e coloque os mp3 nelas."
                    )
                } else {
                    LazyColumn {
                        androidx.compose.foundation.lazy.items(lib.keys.toList()) { name ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { open = name }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${lib[name]?.size ?: 0} músicas" + if (st.playlist == name) " • tocando" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                val tracks = lib[current].orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { MusicPlayer.playPlaylist(current) }) { Text("Tocar tudo") }
                }
                LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    itemsIndexed(tracks, key = { _, t -> t.path }) { i, t ->
                        val now = st.playlist == current && st.index == i
                        Text(
                            text = (if (now) "▶ " else "") + t.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { MusicPlayer.playPlaylist(current, i) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }

        NowPlaying()
    }
}

@Composable
private fun NowPlaying() {
    val st by MusicPlayer.state.collectAsState()
    var pos by remember { mutableIntStateOf(0) }
    var dur by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    // Só atualiza a barra enquanto esta tela existe e está tocando (sem timer fora daqui).
    LaunchedEffect(st.playing, st.title) {
        while (true) {
            if (!dragging) {
                pos = MusicPlayer.positionMs()
                dur = MusicPlayer.durationMs()
            }
            delay(500)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(if (st.title.isEmpty()) "Nada tocando" else st.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    Slider(
        value = if (dur > 0) pos.toFloat() / dur else 0f,
        onValueChange = { dragging = true; pos = (it * dur).toInt() },
        onValueChangeFinished = { MusicPlayer.seekTo(pos); dragging = false },
        enabled = dur > 0
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { MusicPlayer.prev() }) { Text("◀◀") }
        Button(onClick = { MusicPlayer.toggle() }) { Text(if (st.playing) "❚❚ Pausar" else "▶ Tocar") }
        OutlinedButton(onClick = { MusicPlayer.next() }) { Text("▶▶") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        if (st.shuffle) Button(onClick = { MusicPlayer.toggleShuffle() }) { Text("Aleatório") }
        else OutlinedButton(onClick = { MusicPlayer.toggleShuffle() }) { Text("Aleatório") }
        if (st.repeatOne) Button(onClick = { MusicPlayer.toggleRepeat() }) { Text("Repetir 1") }
        else OutlinedButton(onClick = { MusicPlayer.toggleRepeat() }) { Text("Repetir 1") }
    }
    Text("Volume ${(st.volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
    Slider(value = st.volume, onValueChange = { MusicPlayer.setVolume(it) })
}
