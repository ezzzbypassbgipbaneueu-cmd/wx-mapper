package com.wx.mapper.util

import android.os.Environment
import android.os.StatFs
import com.wx.mapper.data.external.ShizukuShell

/** Limpa o cache de TODOS os apps com `pm trim-caches` (via Shizuku). Chamar fora da thread principal. */
object CacheCleaner {
    data class Result(val ok: Boolean, val freedBytes: Long, val message: String)

    // Pede para liberar um espaço enorme: o sistema esvazia todo o cache até onde der.
    private const val COMMAND = "pm trim-caches 99999999999"

    fun clean(): Result {
        if (!ShizukuShell.isAvailable()) {
            return Result(false, 0L, "Shizuku não está ativo ou o WX está sem permissão.")
        }
        val before = freeBytes()
        val r = ShizukuShell.exec(COMMAND, timeoutSec = 90)
        if (!r.ok) {
            val detail = r.err.trim().ifBlank { r.out.trim() }.take(140)
            return Result(false, 0L, "Não consegui limpar o cache (código ${r.exitCode}): $detail")
        }
        return Result(true, (freeBytes() - before).coerceAtLeast(0L), "")
    }

    private fun freeBytes(): Long = try {
        StatFs(Environment.getDataDirectory().path).availableBytes
    } catch (_: Throwable) {
        0L
    }
}
