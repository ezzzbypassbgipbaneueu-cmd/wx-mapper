package com.wx.mapper.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * "Mostrar toques" próprio. O do Android (Opções do desenvolvedor) só desenha os toques que vêm do
 * driver da tela; toques injetados pelo WX (via Shizuku) não passam por lá, por isso não aparecia.
 *
 * Aqui o círculo é desenhado numa janela de overlay separada, sem toque e SEM exclusão de captura:
 * aparece na gravação de tela mesmo com o "modo gravação" escondendo os botões do WX.
 */
object TapIndicator {
    private const val PREFS = "wx_tap_indicator"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var view: TapView? = null
    private var running = false

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        val app = ctx.applicationContext
        main.post { if (on && running) attach(app) else if (!on) detach() }
    }

    /** Chamado pelo serviço do overlay (thread principal). */
    fun start(ctx: Context) {
        running = true
        if (isEnabled(ctx)) attach(ctx.applicationContext)
    }

    fun stop() {
        running = false
        detach()
    }

    /** Chamado pelo injetor a cada MotionEvent enviado ao sistema (qualquer thread). */
    fun onMotion(e: MotionEvent) {
        val v = view ?: return
        val n = e.pointerCount
        val ids = IntArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            ids[i] = e.getPointerId(i)
            xs[i] = e.getX(i)
            ys[i] = e.getY(i)
        }
        val action = e.actionMasked
        val actionId = if (e.actionIndex in 0 until n) ids[e.actionIndex] else ids.firstOrNull() ?: return
        v.post { v.update(action, actionId, ids, xs, ys) }
    }

    private fun attach(app: Context) {
        if (view != null) return
        try {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = TapView(app)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "WX tap indicator"
                if (Build.VERSION.SDK_INT >= 30) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            wm.addView(v, lp)
            view = v
        } catch (_: Throwable) {
            view = null
        }
    }

    private fun detach() {
        val v = view ?: return
        view = null
        try {
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {
        }
    }

    private class TapView(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val radius = 22f * density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val held = HashMap<Int, PointF>()
        private class Ripple(val x: Float, val y: Float, val start: Long)
        private val ripples = ArrayList<Ripple>()
        private val loc = IntArray(2)

        fun update(action: Int, actionId: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
            val idx = ids.indexOf(actionId)
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (idx >= 0) held[actionId] = PointF(xs[idx], ys[idx])
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in ids.indices) held.getOrPut(ids[i]) { PointF() }.set(xs[i], ys[i])
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val p = held.remove(actionId)
                    val x = p?.x ?: if (idx >= 0) xs[idx] else return
                    val y = p?.y ?: if (idx >= 0) ys[idx] else return
                    ripples += Ripple(x, y, SystemClock.uptimeMillis())
                }
                MotionEvent.ACTION_CANCEL -> {
                    val now = SystemClock.uptimeMillis()
                    held.values.forEach { ripples += Ripple(it.x, it.y, now) }
                    held.clear()
                }
            }
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val now = SystemClock.uptimeMillis()
            held.values.forEach { p ->
                fill.color = Color.argb(110, 255, 255, 255)
                ring.color = Color.argb(200, 255, 255, 255)
                canvas.drawCircle(p.x, p.y, radius, fill)
                canvas.drawCircle(p.x, p.y, radius, ring)
            }
            val it = ripples.iterator()
            while (it.hasNext()) {
                val r = it.next()
                val t = (now - r.start) / RIPPLE_MS.toFloat()
                if (t >= 1f) {
                    it.remove()
                    continue
                }
                val a = (1f - t)
                fill.color = Color.argb((110 * a).toInt(), 255, 255, 255)
                ring.color = Color.argb((200 * a).toInt(), 255, 255, 255)
                val rad = radius * (1f + 0.8f * t)
                canvas.drawCircle(r.x, r.y, rad, fill)
                canvas.drawCircle(r.x, r.y, rad, ring)
            }
            if (held.isNotEmpty() || ripples.isNotEmpty()) postInvalidateOnAnimation()
        }

        private companion object {
            const val RIPPLE_MS = 420L
        }
    }
}
