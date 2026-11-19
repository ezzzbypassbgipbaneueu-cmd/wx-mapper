package com.wx.mapper.data.external

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(val exitCode: Int, val out: String, val err: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Roda comandos de shell com a permissão do Shizuku (mesmo nível do ADB).
 * Usa `Shizuku.newProcess` por reflexão: ele é privado na API 13, mas continua funcionando.
 */
object ShizukuShell {

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    /** true se o Shizuku está rodando e o WX tem permissão. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Só deixa passar nomes de pacote válidos: nada de injetar comando no shell. */
    fun isSafePackageName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 200 && Regex("^[A-Za-z0-9_.]+$").matches(name)

    fun exec(command: String, timeoutSec: Long = 15): ShellResult {
        return try {
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = thread(isDaemon = true) {
                process.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
            }
            val errThread = thread(isDaemon = true) {
                process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
            }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            outThread.join(1000)
            errThread.join(1000)
            ShellResult(if (finished) process.exitValue() else -1, out.toString(), err.toString())
        } catch (t: Throwable) {
            ShellResult(-1, "", t.message ?: t.javaClass.simpleName)
        }
    }
}
