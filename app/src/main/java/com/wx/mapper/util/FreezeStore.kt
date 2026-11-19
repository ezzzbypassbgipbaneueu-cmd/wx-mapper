package com.wx.mapper.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.wx.mapper.data.external.ShizukuShell

/**
 * Guarda e aplica o "congelamento" de apps (pm suspend via Shizuku).
 *
 *  - frozen      : tudo que está congelado agora (manual + automático)
 *  - autoList    : lista que o modo automático congela quando um jogo do WX abre
 *  - autoFrozen  : o que o modo automático congelou (só isso é descongelado ao sair do jogo)
 *  - autoEnabled : liga/desliga o modo automático
 */
object FreezeStore {
    private const val PREFS = "wx_frozen"
    private const val KEY_FROZEN = "frozen"
    private const val KEY_AUTO_LIST = "auto_list"
    private const val KEY_AUTO_FROZEN = "auto_frozen"
    private const val KEY_AUTO_ENABLED = "auto_enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getSet(ctx: Context, key: String): Set<String> =
        prefs(ctx).getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun putSet(ctx: Context, key: String, set: Set<String>) {
        prefs(ctx).edit().putStringSet(key, set.toSet()).apply()
    }

    fun frozen(ctx: Context): Set<String> = getSet(ctx, KEY_FROZEN)

    fun setFrozen(ctx: Context, set: Set<String>) {
        putSet(ctx, KEY_FROZEN, set)
        // O que o automático guardou nunca pode ter sobrado fora de "congelados".
        putSet(ctx, KEY_AUTO_FROZEN, getSet(ctx, KEY_AUTO_FROZEN).intersect(set))
    }

    fun autoList(ctx: Context): Set<String> = getSet(ctx, KEY_AUTO_LIST)
    fun setAutoList(ctx: Context, set: Set<String>) = putSet(ctx, KEY_AUTO_LIST, set)

    fun autoFrozen(ctx: Context): Set<String> = getSet(ctx, KEY_AUTO_FROZEN)

    fun autoEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_ENABLED, false)
    fun setAutoEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Congela os pacotes e devolve só os que deram certo. [auto] = marcado como congelado pelo automático. */
    fun freeze(ctx: Context, packages: Collection<String>, auto: Boolean): List<String> {
        val done = packages.filter { pkg ->
            ShizukuShell.isSafePackageName(pkg) && ShizukuShell.exec("pm suspend --user 0 $pkg").ok
        }
        if (done.isNotEmpty()) {
            putSet(ctx, KEY_FROZEN, frozen(ctx) + done)
            if (auto) putSet(ctx, KEY_AUTO_FROZEN, getSet(ctx, KEY_AUTO_FROZEN) + done)
        }
        return done
    }

    /** Descongela e devolve os que deram certo. App desinstalado conta como resolvido. */
    fun unfreeze(ctx: Context, packages: Collection<String>): List<String> {
        val done = packages.filter { pkg ->
            !isInstalled(ctx, pkg) ||
                (ShizukuShell.isSafePackageName(pkg) && ShizukuShell.exec("pm unsuspend --user 0 $pkg").ok)
        }
        if (done.isNotEmpty()) {
            putSet(ctx, KEY_FROZEN, frozen(ctx) - done.toSet())
            putSet(ctx, KEY_AUTO_FROZEN, getSet(ctx, KEY_AUTO_FROZEN) - done.toSet())
        }
        return done
    }

    /** Pacotes que NUNCA devem ser parados/congelados (quebrariam o aparelho ou o próprio WX). */
    fun protectedPackages(ctx: Context): Set<String> {
        val set = mutableSetOf(
            ctx.packageName,
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.providers.media",
            "moe.shizuku.privileged.api",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.webview",
            "com.android.webview"
        )
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName?.let { set += it }
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.let { set += it }
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.forEach { service -> set += service.substringBefore('/') }
        } catch (_: Exception) {
        }
        return set
    }
}
