package com.wx.mapper.util

import android.view.KeyEvent
import com.wx.mapper.domain.KEYCODE_LMC
import com.wx.mapper.domain.KEYCODE_MMC
import com.wx.mapper.domain.KEYCODE_RMC

fun String.capitalizeFirst():String{
    return this.lowercase().replaceFirstChar { it.titlecaseChar() }
}
fun Int.keyCodeToString():String{
    return when(this){
        KEYCODE_LMC -> "LMB"
        KEYCODE_RMC -> "RMB"
        KEYCODE_MMC -> "MMB"
        else-> KeyEvent.keyCodeToString(this).replace("KEYCODE_","").takeIf { it!="1001" }?:"Unknown"
    }
}