package com.wx.mapper.wx

import androidx.compose.ui.geometry.Offset
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
