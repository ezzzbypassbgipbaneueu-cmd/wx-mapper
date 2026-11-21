package com.wx.mapper.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class Track(val path: String, val title: String)

data class MusicState(
    val playlist: String? = null,
    val title: String = "",
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val repeatOne: Boolean = false,
    val volume: Float = 0.8f,
    val index: Int = -1,
    val count: Int = 0
)

/**
 * Player leve e global (um MediaPlayer só, sem serviço extra: o processo já fica vivo pelo overlay).
 *
 * Otimizações:
 *  - A biblioteca é varrida UMA vez em background e guardada em memória; o título é o nome do
 *    arquivo (nada de ler tag por faixa, que é lento em pasta grande).
 *  - Um MediaPlayer reaproveitado com prepareAsync (não trava a thread principal).
 *  - Posição/duração são lidas só por quem está olhando (a tela de música), sem timer rodando.
 *  - Não pede foco de áudio: toca junto com o jogo em vez de pausar o som dele.
 *  - Pausa sozinho ao desconectar o fone.
 */
object MusicPlayer {
    val ROOT: File = File("/storage/emulated/0/msc")
    private val EXT = setOf("mp3", "m4a", "ogg", "wav", "flac", "opus", "aac")
    private const val PREFS = "wx_music"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var wantPlay = false

    private var queue: List<Track> = emptyList()
    private var order: IntArray = IntArray(0)
    private var pos = -1 // posição dentro de [order]

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private val _library = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val library: StateFlow<Map<String, List<Track>>> = _library.asStateFlow()

    @Volatile var scanned = false
        private set

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val p = prefs()
        _state.value = _state.value.copy(
            shuffle = p.getBoolean("shuffle", false),
            repeatOne = p.getBoolean("repeat", false),
            volume = p.getFloat("volume", 0.8f)
        )
        app!!.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { pause() }
        }, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    /** Permissão para ler áudio em /storage/emulated/0 (Android 13+: READ_MEDIA_AUDIO). */
    fun hasAccess(ctx: Context): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun permission(): String =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun prefs() = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- biblioteca ----

    /** Varre /msc: cada subpasta é uma playlist; arquivos soltos na raiz viram "(raiz)". Chamar fora da main. */
    fun scan(): Map<String, List<Track>> {
        val out = linkedMapOf<String, List<Track>>()
        val dirs = ROOT.listFiles()?.sortedWith(naturalOrder) ?: emptyList()
        val rootTracks = dirs.filter { it.isFile }.toTracks()
        dirs.filter { it.isDirectory }.forEach { d ->
            val files = d.walkTopDown().maxDepth(3).filter { it.isFile }.toList().sortedWith(naturalOrder)
            val tracks = files.toTracks()
            if (tracks.isNotEmpty()) out[d.name] = tracks
        }
        if (rootTracks.isNotEmpty()) out["(raiz)"] = rootTracks
        _library.value = out
        scanned = true
        return out
    }

    private val naturalOrder = Comparator<File> { a, b -> compareNatural(a.name, b.name) }

    private fun List<File>.toTracks() =
        filter { it.extension.lowercase() in EXT }.map { Track(it.absolutePath, it.nameWithoutExtension) }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var si = i; while (si < a.length && a[si].isDigit()) si++
                var sj = j; while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).trimStart('0')
                val nb = b.substring(j, sj).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = si; j = sj
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    // ---- controles (qualquer thread) ----

    fun playPlaylist(name: String, startIndex: Int = 0) {
        val tracks = _library.value[name] ?: return
        main.post {
            queue = tracks
            rebuildOrder(startAt = startIndex.coerceIn(0, tracks.size - 1))
            _state.value = _state.value.copy(playlist = name, count = tracks.size)
            prefs().edit().putString("last_playlist", name).apply()
            startCurrent(true)
        }
    }

    fun lastPlaylist(): String? = app?.let { prefs().getString("last_playlist", null) }

    fun toggle() = main.post {
        if (queue.isEmpty()) {
            val name = lastPlaylist()?.takeIf { it in _library.value } ?: _library.value.keys.firstOrNull() ?: return@post
            playPlaylist(name)
            return@post
        }
        if (_state.value.playing) pauseNow() else resumeNow()
    }

    fun pause() = main.post { if (_state.value.playing) pauseNow() }

    fun next() = main.post { advance(+1, auto = false) }
    fun prev() = main.post {
        // Depois de 3 s de faixa, "anterior" volta ao começo dela (como em qualquer player).
        val cur = mp
        if (prepared && cur != null && cur.currentPosition > 3000) cur.seekTo(0) else advance(-1, auto = false)
    }

    fun seekTo(ms: Int) = main.post { if (prepared) mp?.seekTo(ms) }

    fun setVolume(v: Float) = main.post {
        val x = v.coerceIn(0f, 1f)
        mp?.setVolume(x, x)
        _state.value = _state.value.copy(volume = x)
        prefs().edit().putFloat("volume", x).apply()
    }

    fun volumeStep(delta: Float) = setVolume(_state.value.volume + delta)

    fun toggleShuffle() = main.post {
        val on = !_state.value.shuffle
        _state.value = _state.value.copy(shuffle = on)
        prefs().edit().putBoolean("shuffle", on).apply()
        if (queue.isNotEmpty()) rebuildOrder(startAt = order.getOrNull(pos) ?: 0)
    }

    fun toggleRepeat() = main.post {
        val on = !_state.value.repeatOne
        _state.value = _state.value.copy(repeatOne = on)
        prefs().edit().putBoolean("repeat", on).apply()
    }

    /** Lido pela tela de música enquanto ela está aberta. */
    fun positionMs(): Int = try { if (prepared) mp?.currentPosition ?: 0 else 0 } catch (_: Exception) { 0 }
    fun durationMs(): Int = try { if (prepared) mp?.duration ?: 0 else 0 } catch (_: Exception) { 0 }

    // ---- internos (main) ----

    private fun rebuildOrder(startAt: Int) {
        val n = queue.size
        order = IntArray(n) { it }
        if (_state.value.shuffle && n > 1) {
            val rest = (0 until n).filter { it != startAt }.shuffled()
            order = (listOf(startAt) + rest).toIntArray()
            pos = 0
        } else {
            pos = startAt
        }
    }

    private fun advance(step: Int, auto: Boolean) {
        if (queue.isEmpty()) return
        if (auto && _state.value.repeatOne) { startCurrent(true); return }
        val n = order.size
        pos = ((pos + step) % n + n) % n
        startCurrent(true)
    }

    private fun startCurrent(play: Boolean) {
        val track = queue.getOrNull(order.getOrNull(pos) ?: return) ?: return
        wantPlay = play
        prepared = false
        _state.value = _state.value.copy(title = track.title, index = order[pos], playing = false)
        try {
            val player = mp ?: MediaPlayer().also {
                it.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                it.setOnPreparedListener { p ->
                    prepared = true
                    val v = _state.value.volume
                    p.setVolume(v, v)
                    if (wantPlay) { p.start(); _state.value = _state.value.copy(playing = true) }
                }
                it.setOnCompletionListener { advance(+1, auto = true) }
                it.setOnErrorListener { _, _, _ -> advance(+1, auto = true); true } // pula arquivo ruim
                mp = it
            }
            player.reset()
            player.setDataSource(track.path)
            player.prepareAsync()
        } catch (_: Exception) {
            // arquivo sumiu/corrompido: tenta a próxima, sem loop infinito se só há 1 faixa
            if (queue.size > 1) advance(+1, auto = true)
        }
    }

    private fun pauseNow() {
        wantPlay = false
        try { if (prepared) mp?.pause() } catch (_: Exception) {}
        _state.value = _state.value.copy(playing = false)
    }

    private fun resumeNow() {
        wantPlay = true
        try {
            if (prepared) { mp?.start(); _state.value = _state.value.copy(playing = true) }
        } catch (_: Exception) {}
    }

    fun release() = main.post {
        try { mp?.release() } catch (_: Exception) {}
        mp = null; prepared = false; wantPlay = false
        _state.value = _state.value.copy(playing = false)
    }
}
