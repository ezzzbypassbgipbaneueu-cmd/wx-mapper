package com.wx.mapper.util

import android.util.Log
import android.view.SurfaceControl
import android.view.View
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Tenta marcar a camada (SurfaceControl) da janela de um overlay como SKIP_SCREENSHOT, para o
 * sistema não incluí-la em capturas/gravações. Usa APIs OCULTAS (@hide) por reflexão, então pode
 * falhar em alguns aparelhos/versões: nesse caso devolve false e o chamador deve cair no modo
 * "só botões".
 */
object ScreenshotExclusion {
    private const val TAG = "ScreenshotExclusion"
    @Volatile private var exempted = false

    private fun ensureHiddenApiAccess() {
        if (exempted) return
        try { HiddenApiBypass.addHiddenApiExemptions("") } catch (t: Throwable) { Log.w(TAG, "exemptions: $t") }
        exempted = true
    }

    /** Aplica/remove o flag na janela da [view]. A view precisa estar anexada e já desenhada. */
    fun setSkip(view: View, skip: Boolean): Boolean {
        if (!view.isAttachedToWindow) return false
        return try {
            ensureHiddenApiAccess()
            val root = View::class.java.getMethod("getViewRootImpl").invoke(view) ?: return false
            val sc = findSurfaceControl(root) ?: return false
            if (!sc.isValid) return false
            val tx = SurfaceControl.Transaction()
            val m = SurfaceControl.Transaction::class.java.getMethod(
                "setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType
            )
            m.invoke(tx, sc, skip)
            tx.apply()
            tx.close()
            Log.i(TAG, "setSkipScreenshot($skip) aplicado")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setSkipScreenshot indisponível: $t")
            false
        }
    }

    private fun findSurfaceControl(viewRootImpl: Any): SurfaceControl? {
        val cls = viewRootImpl.javaClass
        try {
            (cls.getMethod("getSurfaceControl").invoke(viewRootImpl) as? SurfaceControl)?.let { return it }
        } catch (_: Throwable) { }
        return try {
            val f = cls.getDeclaredField("mSurfaceControl").apply { isAccessible = true }
            f.get(viewRootImpl) as? SurfaceControl
        } catch (_: Throwable) { null }
    }
}
