package com.wx.mapper.music

import android.view.KeyEvent

/**
 * Atalhos globais de música enquanto o mapeador está capturando o teclado:
 *  Ctrl+Alt+Espaço = tocar/pausar, Ctrl+Alt+→ = próxima, Ctrl+Alt+← = anterior,
 *  Ctrl+Alt+↑ / ↓ = volume. Teclas de mídia do teclado também funcionam.
 * Retorna true quando consumiu a tecla (down e up), para ela não chegar ao jogo.
 */
object MusicHotkeys {
    fun handle(e: KeyEvent): Boolean {
        val media = when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> 1
            KeyEvent.KEYCODE_MEDIA_NEXT -> 2
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 3
            else -> 0
        }
        val combo = e.isCtrlPressed && e.isAltPressed
        val code = e.keyCode
        val isComboKey = combo && (code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_DPAD_LEFT ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
        if (media == 0 && !isComboKey) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val volume = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (e.repeatCount > 0 && !volume) return true
        when {
            media == 1 || (isComboKey && code == KeyEvent.KEYCODE_SPACE) -> MusicPlayer.toggle()
            media == 2 || (isComboKey && code == KeyEvent.KEYCODE_DPAD_RIGHT) -> MusicPlayer.next()
            media == 3 || (isComboKey && code == KeyEvent.KEYCODE_DPAD_LEFT) -> MusicPlayer.prev()
            code == KeyEvent.KEYCODE_DPAD_UP -> MusicPlayer.volumeStep(+0.05f)
            code == KeyEvent.KEYCODE_DPAD_DOWN -> MusicPlayer.volumeStep(-0.05f)
        }
        return true
    }
}
