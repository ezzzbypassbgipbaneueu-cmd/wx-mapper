package com.wx.mapper.wx

import androidx.compose.ui.geometry.Offset
import com.wx.mapper.autoclicker.AutoClicker
import com.wx.mapper.autoclicker.AutoClickerConfig
import com.wx.mapper.autoclicker.ClickPositionMode
import com.wx.mapper.domain.KEYCODE_LMC
import com.wx.mapper.domain.KEYCODE_RMC
import com.wx.mapper.input.WxActions
import com.wx.mapper.macros.Macro
import com.wx.mapper.macros.MacroAction
import com.wx.mapper.macros.MacroPlayer
import com.wx.mapper.model.AppConfig
import com.wx.mapper.model.EventInjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

private class FakeInjector : EventInjector {
    val downs = mutableListOf<Pair<Int, Offset>>()
    var releases = 0
    private val active = HashSet<Int>()
    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) {}
    override fun transFormGesture(pointerID: Int, position: Offset) {}
    override fun releaseGesture(pointerID: Int) {}
    override fun injectPointer(pointerID: Int, o1: Offset, o2: Offset) { injectPointer(pointerID, o1) }
    override fun injectPointer(pointerID: Int, position: Offset) { downs += pointerID to position; active += pointerID }
    override fun updatePointerPosition(pointerID: Int, position: Offset) {}
    override fun clear() {}
    override fun releasePointer(pointerID: Int) { if (active.remove(pointerID)) releases++ }
    override suspend fun injected(pointerID: Int) = pointerID in active
    fun anyActive() = active.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class WxTests {

    @Test
    fun autoclicker_30cps_clicks_about_30_per_second_and_stops() = runTest {
        val inj = FakeInjector()
        val clicker = AutoClicker(backgroundScope, inj)
        clicker.start(AutoClickerConfig(cps = 30f)) { Offset(5f, 6f) }
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue("cliques=${inj.downs.size}", inj.downs.size in 28..32)
        assertTrue(clicker.isRunning.value)

        clicker.stop()
        runCurrent()
        val n = inj.downs.size
        advanceTimeBy(500); runCurrent()
        assertEquals(n, inj.downs.size)          // parou
        assertFalse(inj.anyActive())              // ponteiro solto
        assertFalse(clicker.isRunning.value)
    }

    @Test
    fun cps_is_clamped() {
        assertEquals(200f, AutoClickerConfig(cps = 9999f).sanitized().cps)
        assertEquals(1f, AutoClickerConfig(cps = 0f).sanitized().cps)
    }

    @Test
    fun rightButton_hold_starts_release_stops_and_repeat_is_ignored() = runTest {
        val inj = FakeInjector()
        val wx = WxActions(inj, { Offset(1f, 1f) }, backgroundScope)
        wx.autoClickerConfig = AutoClickerConfig(triggerKeyCode = KEYCODE_RMC)
        assertTrue(wx.onTrigger(KEYCODE_RMC, true))
        assertTrue(wx.onTrigger(KEYCODE_RMC, true)) // auto-repeat
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertTrue(wx.autoClicker.isRunning.value)
        assertTrue(wx.onTrigger(KEYCODE_RMC, false))
        runCurrent()
        assertFalse(wx.autoClicker.isRunning.value)
        assertFalse(inj.anyActive())
    }

    @Test
    fun other_buttons_and_disabled_config_are_not_consumed() = runTest {
        val wx = WxActions(FakeInjector(), { Offset.Zero }, backgroundScope)
        assertFalse(wx.onTrigger(KEYCODE_LMC, true))
        wx.autoClickerConfig = AutoClickerConfig(enabled = false)
        assertFalse(wx.onTrigger(KEYCODE_RMC, true))
    }

    @Test
    fun mouse_button_state_safety_net_stops_when_release_is_lost() = runTest {
        val wx = WxActions(FakeInjector(), { Offset.Zero }, backgroundScope)
        wx.onTrigger(KEYCODE_RMC, true)
        runCurrent()
        assertTrue(wx.autoClicker.isRunning.value)
        wx.syncMouseButtons(0) // nenhum botão pressionado de fato
        runCurrent()
        assertFalse(wx.autoClicker.isRunning.value)
    }

    @Test
    fun fixed_position_is_used() = runTest {
        val inj = FakeInjector()
        val wx = WxActions(inj, { Offset(1f, 1f) }, backgroundScope)
        wx.autoClickerConfig = AutoClickerConfig(positionMode = ClickPositionMode.FIXED, fixedX = 300f, fixedY = 400f)
        wx.onTrigger(KEYCODE_RMC, true)
        runCurrent()
        assertEquals(Offset(300f, 400f), inj.downs.first().second)
        wx.stopAll()
    }

    @Test
    fun macro_plays_sequence_with_timing_and_releases() = runTest {
        val inj = FakeInjector()
        val player = MacroPlayer(backgroundScope, inj)
        val m = Macro(1, "m", actions = listOf(
            MacroAction.Tap(10f, 20f, 30), MacroAction.Wait(100), MacroAction.Tap(30f, 40f, 30)))
        assertTrue(player.play(m))
        runCurrent()
        assertEquals(1, inj.downs.size)
        advanceTimeBy(131); runCurrent()
        assertEquals(2, inj.downs.size)
        advanceTimeBy(31); runCurrent()
        assertNull(player.playingMacroId.value)
        assertFalse(inj.anyActive())
    }

    @Test
    fun looping_macro_stops_on_toggle() = runTest {
        val inj = FakeInjector()
        val player = MacroPlayer(backgroundScope, inj)
        val m = Macro(2, "loop", loop = true, actions = listOf(MacroAction.Tap(1f, 1f, 10), MacroAction.Wait(40)))
        player.toggle(m); runCurrent(); advanceTimeBy(200); runCurrent()
        assertTrue(inj.downs.size >= 3)
        player.toggle(m); runCurrent()
        val n = inj.downs.size
        advanceTimeBy(200); runCurrent()
        assertEquals(n, inj.downs.size)
        assertNull(player.playingMacroId.value)
    }

    @Test
    fun macro_validation() {
        assertNotNull(Macro(1, " ", actions = listOf(MacroAction.Wait(10))).validate())
        assertNotNull(Macro(1, "a").validate())
        assertNotNull(Macro(1, "a", loop = true, actions = listOf(MacroAction.Wait(1))).validate())
        assertNull(Macro(1, "a", loop = true, actions = listOf(MacroAction.Wait(50))).validate())
    }

    @Test
    fun config_survives_json_roundtrip_and_old_configs_still_load() {
        val cfg = AppConfig.Default.copy(
            autoClicker = AutoClickerConfig(cps = 30f),
            macros = listOf(Macro(1, "x", 51, true, listOf(MacroAction.Tap(1f, 2f), MacroAction.Wait(5))))
        )
        val back = Json.decodeFromString<AppConfig>(Json.encodeToString(AppConfig.serializer(), cfg))
        assertEquals(cfg, back)
        val old = """{"shootingModeKeyCode":256,"fireKeyCode":64,"scopeKeyCode":128,"buttonScale":1.0}"""
        val loaded = Json.decodeFromString<AppConfig>(old)
        assertEquals(30f, loaded.autoClicker.cps)
        assertEquals(KEYCODE_RMC, loaded.autoClicker.triggerKeyCode)
    }
}
