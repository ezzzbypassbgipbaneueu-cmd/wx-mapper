package com.wx.mapper.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.wx.mapper.data.external.ShizukuShell
import com.wx.mapper.util.FreezeStore
import com.wx.mapper.util.GameOptimizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Sessão de jogo: ao abrir um jogo cadastrado congela a "lista automática" e aplica a otimização;
 * ao sair, descongela e desfaz a otimização.
 *
 * Detecção de saída com DUAS fontes independentes (antes só havia a primeira):
 *  1. Eventos de janela do serviço de acessibilidade (rápido).
 *  2. Vigia: enquanto houver algo congelado/alterado, pergunta ao sistema (via Shizuku) qual é a
 *     activity no topo, a cada [WATCHDOG_MS]. Pega os casos em que o evento não chega, e também
 *     quando você sai do jogo para o próprio WX (eventos do WX são ignorados na fonte 1).
 * O descongelar tenta de novo se falhar, e o vigia continua tentando até conseguir.
 */
class AutoFreezeManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isGamePackage: (suspend (String) -> Boolean)? = null
) {
    private val lock = Mutex()
    private var leaveJob: Job? = null
    private var watchdog: Job? = null
    private var gamePkg: String? = null
    private val main = Handler(Looper.getMainLooper())

    // Sobrou coisa de uma sessão anterior (app fechou no meio): a próxima saída do jogo desfaz.
    @Volatile
    private var inGame = pendingWork()

    init {
        if (inGame) startWatchdog()
    }

    private fun pendingWork() =
        FreezeStore.autoFrozen(context).isNotEmpty() || GameOptimizer.hasPendingRestore(context)

    private fun active() = FreezeStore.autoEnabled(context) || GameOptimizer.enabled(context)

    fun onForeground(pkg: String, isGame: Boolean) {
        if (!active()) {
            if (inGame) scope.launch(Dispatchers.IO) { release(silent = true) }
            return
        }
        if (isGame) {
            cancelLeave()
            gamePkg = pkg
            if (!inGame) {
                inGame = true
                scope.launch(Dispatchers.IO) { enterGame(pkg) }
            }
            startWatchdog()
        } else if (inGame) {
            // isRealApp consulta PackageManager/Settings: fora da thread principal.
            scope.launch(Dispatchers.IO) { if (inGame && isRealApp(pkg)) scheduleLeave() }
        }
    }

    /** Chamado ao fechar o WX: nada pode ficar congelado/alterado sem ninguém para desfazer. */
    fun releaseNow() {
        watchdog?.cancel()
        val pending = FreezeStore.autoFrozen(context)
        if (pending.isEmpty() && !GameOptimizer.hasPendingRestore(context)) return
        thread(isDaemon = true) {
            if (pending.isNotEmpty()) FreezeStore.unfreeze(context, pending)
            GameOptimizer.restore(context)
        }
    }

    /** Botão da notificação: descongela agora, sem esperar. */
    fun releaseManually() {
        cancelLeave()
        scope.launch(Dispatchers.IO) { release(silent = false) }
    }

    // ---- saída ----

    private fun scheduleLeave() = synchronized(this) {
        if (leaveJob != null) return@synchronized
        leaveJob = scope.launch {
            delay(LEAVE_DELAY_MS)
            withContext(Dispatchers.IO) { release(silent = false) }
        }
    }

    private fun cancelLeave() = synchronized(this) {
        leaveJob?.cancel()
        leaveJob = null
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

    // ---- vigia ----

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WATCHDOG_MS)
                if (!inGame && !pendingWork()) break // nada mais a vigiar
                val top = topPackage() ?: continue
                val onGame = top == gamePkg || isGamePackage?.invoke(top) == true
                when {
                    onGame -> {
                        gamePkg = top
                        cancelLeave()
                        if (!inGame && active()) {
                            inGame = true
                            enterGame(top)
                        }
                    }
                    top == "com.android.systemui" || top == "android" -> Unit // sombra de notificação etc.
                    inGame -> scheduleLeave()
                    pendingWork() -> release(silent = true) // sobra de tentativa que falhou: tenta de novo
                }
            }
        }
    }

    /** Pacote da activity retomada no topo, ou null se não deu para saber. */
    private fun topPackage(): String? {
        if (!ShizukuShell.isAvailable()) return null
        val r = ShizukuShell.exec(
            "dumpsys activity activities | grep -E 'topResumedActivity=|ResumedActivity[:=]' | head -n 1", 8
        )
        return Regex("""\su\d+\s+([A-Za-z0-9_.]+)/""").find(r.out)?.groupValues?.get(1)
    }

    // ---- entrar / sair ----

    private suspend fun enterGame(gamePkg: String) = lock.withLock {
        var frozenCount = 0
        if (FreezeStore.autoEnabled(context)) {
            val blocked = FreezeStore.protectedPackages(context) + gamePkg
            val already = FreezeStore.frozen(context)
            val targets = FreezeStore.autoList(context).filter { it !in blocked && it !in already }
            if (targets.isNotEmpty()) {
                val done = FreezeStore.freeze(context, targets, auto = true)
                if (done.isEmpty()) toast("Não consegui congelar os apps (Shizuku ativo?)") else frozenCount = done.size
            }
        }
        val opt = if (GameOptimizer.enabled(context)) GameOptimizer.apply(context, gamePkg) else emptyList()
        val parts = mutableListOf<String>()
        if (frozenCount > 0) parts += "$frozenCount app(s) congelado(s)"
        if (opt.isNotEmpty()) parts += "otimizado"
        if (parts.isNotEmpty()) toast(parts.joinToString(" • "))
    }

    private suspend fun release(silent: Boolean) {
        val game = gamePkg
        lock.withLock {
            synchronized(this) { leaveJob = null }
            inGame = false
            var pending = FreezeStore.autoFrozen(context)
            var total = 0
            var attempt = 0
            while (pending.isNotEmpty() && attempt < 3) {
                total += FreezeStore.unfreeze(context, pending).size
                pending = FreezeStore.autoFrozen(context)
                if (pending.isNotEmpty()) delay(1500)
                attempt++
            }
            GameOptimizer.restore(context)
            if (pending.isNotEmpty()) {
                // O vigia continua tentando; avisa só uma vez.
                if (!silent) toast("${pending.size} app(s) seguem congelados. Tentando de novo…")
            } else if (!silent && total > 0) {
                toast("$total app(s) descongelado(s)")
            }
        }
        // Compila o jogo (1x por versão) com o aparelho ocioso, fora do lock.
        if (game != null && GameOptimizer.enabled(context) &&
            GameOptimizer.isOn(context, GameOptimizer.Opt.COMPILE)
        ) {
            scope.launch(Dispatchers.IO) {
                delay(20_000)
                if (!inGame) GameOptimizer.compile(context, game)
            }
        }
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val LEAVE_DELAY_MS = 6000L
        const val WATCHDOG_MS = 7000L
    }
}
