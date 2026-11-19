package com.wx.mapper.data.external

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

data class ShellResult(val exitCode: Int, val out: String, val err: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Roda comandos de shell com a permissão do Shizuku (mesmo nível do ADB).
 * Mesmo modelo do Hail: pede o processo direto ao serviço do Shizuku (IShizukuService),
 * escreve o comando no stdin do `sh` e lê stdout/stderr.
 */
object ShizukuShell {

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
            val process = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                .newProcess(arrayOf("sh"), null, null)

            // Fechar o stdin manda o EOF: o sh executa o comando e termina.
            ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream).use {
                it.write((command + "\n").toByteArray())
            }

            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = thread(isDaemon = true) {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
                        .bufferedReader().use { out.append(it.readText()) }
                } catch (_: Throwable) {
                }
            }
            val errThread = thread(isDaemon = true) {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
                        .bufferedReader().use { err.append(it.readText()) }
                } catch (_: Throwable) {
                }
            }

            var code = -1
            val waiter = thread(isDaemon = true) {
                code = try {
                    process.waitFor()
                } catch (_: Throwable) {
                    -1
                }
            }
            waiter.join(timeoutSec * 1000)
            val finished = !waiter.isAlive
            if (!finished) {
                try {
                    process.destroy()
                } catch (_: Throwable) {
                }
            }
            outThread.join(1000)
            errThread.join(1000)
            if (finished) {
                try {
                    process.destroy()
                } catch (_: Throwable) {
                }
            }
            val errText = if (finished) err.toString() else "tempo esgotado (${timeoutSec}s)"
            ShellResult(if (finished) code else -1, out.toString(), errText)
        } catch (t: Throwable) {
            ShellResult(-1, "", "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
