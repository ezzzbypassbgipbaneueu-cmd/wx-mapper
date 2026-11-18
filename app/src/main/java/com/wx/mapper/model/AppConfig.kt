package com.wx.mapper.model

import android.view.KeyEvent
import com.wx.mapper.domain.KEYCODE_LMC
import com.wx.mapper.domain.KEYCODE_MMC
import com.wx.mapper.domain.KEYCODE_RMC
import com.wx.mapper.autoclicker.AutoClickerConfig
import com.wx.mapper.macros.Macro
import kotlinx.serialization.Serializable

@Serializable
enum class CursorStyle { ARROW, CROSSHAIR, CIRCLE, DOT, IMAGE }

/** Tamanho visual base dos botões do keymapper (antes 0.75, ficava grande demais). */
const val BUTTON_BASE_SCALE = 0.5f

/** Escala visual final dos botões: escolha do usuário (Settings) x tamanho base reduzido. */
fun AppConfig.visualButtonScale(): Float = buttonScale.coerceIn(0.35f, 2f) * BUTTON_BASE_SCALE

/** Estratégia do modo gravação. */
@Serializable
enum class RecordingHideMode {
    /** Bolha e botões somem (para todos); o cursor continua visível. Sempre funciona. */
    BUTTONS_ONLY,
    /** Tudo visível para você; a camada do overlay é marcada SKIP_SCREENSHOT (API oculta). */
    SKIP_SCREENSHOT,
    /** Tudo visível para você; janelas do overlay com FLAG_SECURE (pode gravar preto). */
    FLAG_SECURE,
}

@Serializable
data class AppConfig(
    val shootingModeKeyCode: Int,
    val fireKeyCode: Int,
    val scopeKeyCode:Int,
    val buttonScale: Float,
    val deleteDataOnRemove: Boolean = Default.deleteDataOnRemove,
    val themePreference: ThemePreference = Default.themePreference,
    val cancellableTouchMode: TouchMode = Default.cancellableTouchMode,
    val normalBtnTouchMode: TouchMode = Default.normalBtnTouchMode,
    val autoClicker: AutoClickerConfig = AutoClickerConfig(),
    val macros: List<Macro> = emptyList(),
    val cursorStyle: CursorStyle = CursorStyle.ARROW,
    val cursorSizeDp: Float = 24f,
    val cursorColor: Int = 0xFFFFFFFF.toInt(),
    val cursorOpacity: Float = 1f,
    /** Imagem personalizada do cursor (arquivo dentro do armazenamento do app). */
    val cursorImagePath: String? = null,
    /** Muda a cada nova imagem, para o overlay recarregar o bitmap. */
    val cursorImageVersion: Long = 0L,
    /** false = ponta (clique) no canto superior esquerdo da imagem; true = no centro. */
    val cursorImageHotspotCenter: Boolean = false,
    /** Modo digitar automático: ao focar um campo de texto, o mapper libera o teclado. */
    val autoTypingMode: Boolean = true,
    /** Tecla que liga o modo digitar manualmente. */
    val typingModeKeyCode: Int = KeyEvent.KEYCODE_F9,
    /** Tecla que esconde/mostra TUDO do keymapper (para gravar a tela). */
    val recordingModeKeyCode: Int = KeyEvent.KEYCODE_F10,
    /** Tecla que começa/termina a gravação de uma macro (dentro do jogo). */
    val macroRecordKeyCode: Int = KeyEvent.KEYCODE_F8,
    /** Como o modo gravação esconde o overlay da gravação de tela. */
    val recordingHideMode: RecordingHideMode = RecordingHideMode.BUTTONS_ONLY,
    /** Modo desempenho: manda menos eventos ao jogo e reduz o detector de acessibilidade. */
    val performanceMode: Boolean = false,
) {
    companion object {
        val Default = AppConfig(
            shootingModeKeyCode = KEYCODE_MMC,
            fireKeyCode = KEYCODE_LMC,
            scopeKeyCode = KEYCODE_RMC,
            buttonScale = 1f,
            deleteDataOnRemove = false,
            themePreference = ThemePreference.DARK,
            cancellableTouchMode = TouchMode.TAP,
            normalBtnTouchMode = TouchMode.TAP
        )
    }

}
