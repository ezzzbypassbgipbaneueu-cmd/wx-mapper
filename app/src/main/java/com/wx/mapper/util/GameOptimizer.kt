package com.wx.mapper.util

import android.content.Context
import com.wx.mapper.data.external.ShizukuShell

/**
 * Otimização do jogo via Shizuku. Versão melhorada do script "WX EXTREME BOOSTER".
 *
 * O que mudou em relação ao script:
 *  - Animações do Android: o script zerava e NUNCA voltava. Aqui os valores originais são salvos e
 *    restaurados quando você sai do jogo.
 *  - `pm trim-caches 99999999999` continua (opção ligada por padrão): é o ajuste que mais rende.
 *  - `sync` foi removido (não faz nada útil aqui).
 *  - Compilação ART (`speed-profile`) agora roda UMA vez por versão do jogo e só depois que você
 *    sai dele (compilar com o jogo aberto gasta CPU justo quando você quer FPS). Timeout longo.
 *  - `am set-inactive` virou `am set-standby-bucket active` (o que realmente tira o app da restrição).
 *  - Novo: `am kill-all` (mata só processos em cache, seguro) e opção de desligar o limitador de
 *    desempenho de jogos da Samsung (GOS), religado ao sair do jogo.
 *
 * Tudo é "melhor esforço": se um comando não existir nesse aparelho, ele é só ignorado.
 */
object GameOptimizer {
    const val DEFAULT_GAME = "com.sandboxol.blockymods.channel.official"
    private const val GOS = "com.samsung.android.game.gos"
    private const val PREFS = "wx_optimizer"

    private val ANIM_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    enum class Opt(val key: String, val default: Boolean, val label: String, val hint: String) {
        TRIM_CACHE("o_trim", true, "Limpar cache de todos os apps (pm trim-caches)", "o ajuste que mais rende; roda ao abrir o jogo"),
        GAME_MODE("o_gamemode", true, "Modo desempenho do Android", "cmd game mode performance"),
        ANIMATIONS("o_anim", true, "Desligar animações durante o jogo", "volta ao normal quando você sai"),
        BUCKET("o_bucket", true, "Tirar o jogo da restrição de bateria", "standby bucket = active"),
        KILL_BG("o_killbg", true, "Limpar processos em cache", "am kill-all (seguro)"),
        COMPILE("o_compile", true, "Compilar o jogo (ART) ao sair", "1 vez por versão, só quando ocioso"),
        GOS("o_gos", false, "Desligar limitador de jogos da Samsung (GOS)", "religa ao sair do jogo")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("enabled", v).apply()

    fun isOn(ctx: Context, o: Opt) = prefs(ctx).getBoolean(o.key, o.default)
    fun setOn(ctx: Context, o: Opt, v: Boolean) = prefs(ctx).edit().putBoolean(o.key, v).apply()

    fun lastGame(ctx: Context): String = prefs(ctx).getString("last_game", null) ?: DEFAULT_GAME

    /** true se mexemos em algo que ainda precisa ser desfeito (animações / GOS). */
    fun hasPendingRestore(ctx: Context): Boolean =
        prefs(ctx).contains("saved_anim") || prefs(ctx).getBoolean("gos_off", false)

    private fun sh(cmd: String, timeout: Long = 15) = ShizukuShell.exec(cmd, timeout)

    /** Aplica as otimizações que valem DURANTE a sessão de jogo. Devolve o que deu certo. */
    fun apply(ctx: Context, pkg: String): List<String> {
        if (!ShizukuShell.isAvailable() || !ShizukuShell.isSafePackageName(pkg)) return emptyList()
        prefs(ctx).edit().putString("last_game", pkg).apply()
        val done = mutableListOf<String>()

        if (isOn(ctx, Opt.ANIMATIONS)) {
            if (!prefs(ctx).contains("saved_anim")) {
                val r = sh(ANIM_KEYS.joinToString("; ") { "settings get global $it" })
                val vals = r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (r.ok && vals.size >= 3) {
                    prefs(ctx).edit().putString("saved_anim", vals.take(3).joinToString("|")).apply()
                }
            }
            // Só mexe se conseguiu guardar o original (senão não teria como devolver).
            if (prefs(ctx).contains("saved_anim") &&
                sh(ANIM_KEYS.joinToString("; ") { "settings put global $it 0" }).ok
            ) done += "animações"
        }
        persistent(ctx, pkg, done)
        if (isOn(ctx, Opt.GOS) && sh("pm disable-user --user 0 $GOS").ok) {
            prefs(ctx).edit().putBoolean("gos_off", true).apply()
            done += "GOS"
        }
        return done
    }

    /** Ajustes que não precisam ser desfeitos (também usados no botão "Otimizar agora"). */
    fun persistent(ctx: Context, pkg: String, done: MutableList<String>) {
        // Primeiro: libera RAM/armazenamento antes dos outros ajustes. Pode demorar, então timeout longo.
        if (isOn(ctx, Opt.TRIM_CACHE) && sh("pm trim-caches 99999999999", timeout = 90).ok) done += "cache"
        if (isOn(ctx, Opt.GAME_MODE) && sh("cmd game mode performance $pkg").ok) done += "modo desempenho"
        if (isOn(ctx, Opt.BUCKET) && sh("am set-standby-bucket $pkg active").ok) done += "bateria"
        if (isOn(ctx, Opt.KILL_BG) && sh("am kill-all").ok) done += "processos em cache"
    }

    /** Desfaz o que [apply] mudou. Pode ser chamado várias vezes sem problema. */
    fun restore(ctx: Context): Boolean {
        var ok = true
        prefs(ctx).getString("saved_anim", null)?.let { saved ->
            val vals = saved.split("|")
            val cmd = ANIM_KEYS.mapIndexed { i, key ->
                val v = vals.getOrNull(i)
                if (v == null || v == "null") "settings delete global $key" else "settings put global $key $v"
            }.joinToString("; ")
            if (sh(cmd).ok) prefs(ctx).edit().remove("saved_anim").apply() else ok = false
        }
        if (prefs(ctx).getBoolean("gos_off", false)) {
            if (sh("pm enable $GOS").ok) prefs(ctx).edit().putBoolean("gos_off", false).apply() else ok = false
        }
        return ok
    }

    /** Compila o jogo no ART, no máximo uma vez por versão (ou sempre, com [force]). */
    fun compile(ctx: Context, pkg: String, force: Boolean = false): Boolean? {
        if (!ShizukuShell.isSafePackageName(pkg)) return null
        val version = try {
            ctx.packageManager.getPackageInfo(pkg, 0).longVersionCode.toString()
        } catch (_: Exception) {
            return null
        }
        val key = "compiled_$pkg"
        if (!force && prefs(ctx).getString(key, null) == version) return null
        val r = sh("cmd package compile -m speed-profile -f $pkg", timeout = 420)
        if (r.ok) prefs(ctx).edit().putString(key, version).apply()
        return r.ok
    }

    /** Botão "Otimizar agora". */
    fun optimizeNow(ctx: Context, pkg: String): String {
        if (!ShizukuShell.isAvailable()) return "Shizuku inativo."
        val done = mutableListOf<String>()
        persistent(ctx, pkg, done)
        if (isOn(ctx, Opt.COMPILE)) {
            when (compile(ctx, pkg, force = true)) {
                true -> done += "compilação"
                else -> {}
            }
        }
        return if (done.isEmpty()) "Nada foi aplicado (comandos não aceitos ou tudo desligado)."
        else "Aplicado: ${done.joinToString(", ")}."
    }
}
