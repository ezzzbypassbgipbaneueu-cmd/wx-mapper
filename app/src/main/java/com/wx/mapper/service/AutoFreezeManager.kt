package com.wx.mapper.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.wx.mapper.util.FreezeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Congela a "lista automática" quando um jogo cadastrado vai para a frente e descongela quando
 * você sai dele (com uma espera de alguns segundos para não piscar à toa).
 * Só reage a apps de verdade: barra de notificações, teclado e diálogos do sistema são ignorados.
 */
class AutoFreezeManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val lock = Mutex()
    private var leaveJob: Job? = null
    private val main = Handler(Looper.getMainLooper())

    // Se sobrou coisa congelada pelo automático (app fechou no meio), consideramos a sessão aberta:
    // a próxima saída do jogo descongela.
    private var inGame = FreezeStore.autoFrozen(context).isNotEmpty()

    fun onForeground(pkg: String, isGame: Boolean) {
        if (!FreezeStore.autoEnabled(context)) {
            if (inGame) scope.launch(Dispatchers.IO) { release(silent = true) }
            return
        }
        if (isGame) {
            leaveJob?.cancel()
            leaveJob = null
            if (!inGame) {
                inGame = true
                scope.launch(Dispatchers.IO) { freezeFor(pkg) }
            }
        } else if (inGame && leaveJob == null && isRealApp(pkg)) {
            leaveJob = scope.launch {
                delay(LEAVE_DELAY_MS)
                withContext(Dispatchers.IO) { release(silent = false) }
            }
        }
    }

    /** Chamado ao fechar o WX: nada pode ficar congelado sem ninguém para descongelar. */
    fun releaseNow() {
        val pending = FreezeStore.autoFrozen(context)
        if (pending.isEmpty()) return
        thread(isDaemon = true) { FreezeStore.unfreeze(context, pending) }
    }

    private fun isRealApp(pkg: String): Boolean {
        if (pkg == context.packageName) return false
        if (pkg in FreezeStore.protectedPackages(context) && !isHome(pkg)) return false
        return isHome(pkg) || context.packageManager.getLaunchIntentForPackage(pkg) != null
    }

    private fun isHome(pkg: String): Boolean = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == pkg
    } catch (_: Exception) {
        false
    }

    private suspend fun freezeFor(gamePkg: String) = lock.withLock {
        val blocked = FreezeStore.protectedPackages(context) + gamePkg
        val already = FreezeStore.frozen(context)
        val targets = FreezeStore.autoList(context).filter { it !in blocked && it !in already }
        if (targets.isEmpty()) return@withLock
        val done = FreezeStore.freeze(context, targets, auto = true)
        toast(if (done.isEmpty()) "Não consegui congelar os apps (Shizuku ativo?)" else "${done.size} app(s) congelado(s) para o jogo")
    }

    private suspend fun release(silent: Boolean) = lock.withLock {
        leaveJob = null
        inGame = false
        val pending = FreezeStore.autoFrozen(context)
        if (pending.isEmpty()) return@withLock
        val done = FreezeStore.unfreeze(context, pending)
        if (!silent && done.isNotEmpty()) toast("${done.size} app(s) descongelado(s)")
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val LEAVE_DELAY_MS = 8000L
    }
}
