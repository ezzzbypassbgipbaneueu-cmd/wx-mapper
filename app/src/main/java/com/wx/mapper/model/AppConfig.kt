package com.wx.mapper.model

import com.wx.mapper.domain.KEYCODE_LMC
import com.wx.mapper.domain.KEYCODE_MMC
import com.wx.mapper.domain.KEYCODE_RMC
import com.wx.mapper.autoclicker.AutoClickerConfig
import com.wx.mapper.macros.Macro
import kotlinx.serialization.Serializable

@Serializable
enum class CursorStyle { ARROW, CROSSHAIR, CIRCLE, DOT }

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
