package com.wx.mapper.wx

import androidx.compose.ui.geometry.Offset
import com.wx.mapper.data.local.toJsonText
import com.wx.mapper.macros.MacroAction
import com.wx.mapper.macros.MacroRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroRecorderTests {

    @Test
    fun records_taps_and_the_gaps_between_them() {
        var t = 0L
        val rec = MacroRecorder { t }
        rec.start()
        t = 100; rec.onDown(Offset(1f, 2f))
        t = 130; rec.onUp()
        t = 330; rec.onDown(Offset(3f, 4f))
        t = 360; rec.onUp()
        val result = rec.stop()
        assertEquals(
            listOf(
                MacroAction.Tap(1f, 2f, 30),
                MacroAction.Wait(200),
                MacroAction.Tap(3f, 4f, 30),
            ),
            result
        )
        assertFalse(rec.isRecording.value)
    }

    @Test
    fun ignores_events_when_not_recording_and_closes_a_pending_press_on_stop() {
        var t = 0L
        val rec = MacroRecorder { t }
        rec.onDown(Offset(9f, 9f))
        rec.onUp()
        assertTrue(rec.stop().isEmpty())

        rec.start()
        t = 10; rec.onDown(Offset(5f, 6f))
        t = 50
        val result = rec.stop()
        assertEquals(listOf<MacroAction>(MacroAction.Tap(5f, 6f, 40)), result)
    }
}

class MacroTriggersTests {
    @Test
    fun picks_first_free_function_key() {
        assertEquals(
            android.view.KeyEvent.KEYCODE_F7,
            com.wx.mapper.macros.MacroTriggers.nextFree(emptySet())
        )
        assertEquals(
            android.view.KeyEvent.KEYCODE_F6,
            com.wx.mapper.macros.MacroTriggers.nextFree(setOf(android.view.KeyEvent.KEYCODE_F7))
        )
    }

    @Test
    fun returns_null_when_everything_is_taken() {
        val all = (android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12).toSet()
        assertEquals(null, com.wx.mapper.macros.MacroTriggers.nextFree(all))
    }
}

class BackupTests {
    @Test
    fun backup_round_trips_config_and_macros() {
        val macro = com.wx.mapper.macros.Macro(
            id = 1, name = "teste", triggerKeyCode = android.view.KeyEvent.KEYCODE_F7,
            actions = listOf(MacroAction.Tap(10f, 20f, 5), MacroAction.Wait(100))
        )
        val config = com.wx.mapper.model.AppConfig.Default.copy(macros = listOf(macro))
        val text = com.wx.mapper.data.local.WxBackup(config = config).toJsonText()
        val back = com.wx.mapper.data.local.parseBackup(text)
        assertEquals(listOf(macro), back.config.macros)
    }

    @Test
    fun rejects_files_that_are_not_a_backup() {
        val failed = runCatching { com.wx.mapper.data.local.parseBackup("{\"foo\": 1}") }.isFailure
        assertTrue(failed)
    }
}
