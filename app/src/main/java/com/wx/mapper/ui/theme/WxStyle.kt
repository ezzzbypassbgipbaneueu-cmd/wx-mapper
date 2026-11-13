package com.wx.mapper.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val WxPurple = Color(0xFF8B5CF6)
val WxBlue = Color(0xFF3B82F6)
val WxBgTop = Color(0xFF12082B)
val WxBgBottom = Color(0xFF07122B)

val WxBackground = Brush.verticalGradient(listOf(WxBgTop, WxBgBottom))
val WxAccent = Brush.horizontalGradient(listOf(WxPurple, WxBlue))

/** Card "vidro": superfície translúcida com borda suave em gradiente. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Brush.linearGradient(listOf(WxPurple.copy(0.6f), WxBlue.copy(0.3f))), shape)
            .padding(16.dp),
        content = content
    )
}
