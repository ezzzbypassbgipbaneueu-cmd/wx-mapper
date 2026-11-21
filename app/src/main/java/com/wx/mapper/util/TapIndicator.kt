package com.wx.mapper.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowManager

/**
 * "Mostrar toques" próprio. O do Android (Opções do desenvolvedor) só desenha os toques que vêm do
 * driver da tela; toques injetados pelo WX (via Shizuku) não passam por lá, por isso não aparecia.
 *
 * Aqui o anel é desenhado numa janela de overlay separada, sem toque e SEM exclusão de captura:
 * aparece na gravação de tela mesmo com o "modo gravação" escondendo os botões do WX.
 */
object TapIndicator {
    private const val PREFS = "wx_tap_indicator"
    const val MODE_OFF = 0
    const val MODE_WINDOW = 1   // janela própria: aparece na gravação mesmo no modo gravação
    const val MODE_INSIDE = 2   // dentro do overlay do WX: não cria janela nova (mais seguro p/ toque)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var view: TapView? = null
    private var attachedInside = false
    private var running = false
    private var containerProvider: () -> ViewGroup? = { null }

    fun mode(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("mode", MODE_OFF)

    fun setMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("mode", mode).apply()
        val app = ctx.applicationContext
        main.post {
            detach()
            if (mode != MODE_OFF && running) attach(app, mode)
        }
    }

    /** Chamado pelo serviço do overlay (thread principal), depois de criar as janelas. */
    fun start(ctx: Context, container: () -> ViewGroup?) {
        running = true
        containerProvider = container
        val m = mode(ctx)
        if (m != MODE_OFF) attach(ctx.applicationContext, m)
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

    private fun attach(app: Context, mode: Int) {
        if (view != null) return
        try {
            val v = TapView(app)
            if (mode == MODE_INSIDE) {
                val c = containerProvider() ?: return
                v.isClickable = false
                v.isFocusable = false
                c.addView(v, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                attachedInside = true
            } else {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                // Mesmas flags do Android-Show-Taps (menos os tipos de janela de sistema, que um app
                // comum não pode usar). alpha < 0.8 = limite do Android 12+ para o overlay NÃO bloquear
                // os toques que atravessam (regra de "untrusted touch"): evita o aviso/clique perdido.
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    title = "WX tap indicator"
                    alpha = 0.78f
                    if (Build.VERSION.SDK_INT >= 30) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        fitInsetsTypes = 0
                    } else if (Build.VERSION.SDK_INT >= 28) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                wm.addView(v, lp)
                attachedInside = false
            }
            view = v
        } catch (_: Throwable) {
            view = null
        }
    }

    private fun detach() {
        val v = view ?: return
        view = null
        try {
            if (attachedInside) (v.parent as? ViewGroup)?.removeView(v)
            else (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {
        }
    }

    /**
     * Visual copiado do projeto Android-Show-Taps: anel (só contorno) de 80 dp com traço de 5 dp,
     * uma cor por dedo, rastro que acompanha o dedo por 200 ms e anel que some em 150 ms ao soltar.
     */
    private class TapView(ctx: Context) : View(ctx) {
        private val dp = ctx.resources.displayMetrics.density
        private val radius = 40f * dp
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private class Pt(val x: Float, val y: Float, val t: Long)
        private class Finger(var x: Float, var y: Float, val trail: ArrayList<Pt> = ArrayList())
        private class Fade(val id: Int, val x: Float, val y: Float, val start: Long)

        private val held = HashMap<Int, Finger>()
        private val fades = ArrayList<Fade>()
        private val oldTrails = ArrayList<Pair<Int, ArrayList<Pt>>>() // rastros de dedos já soltos
        private val loc = IntArray(2)

        private fun color(id: Int) = COLORS[id % COLORS.size]

        fun update(action: Int, actionId: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
            val now = SystemClock.uptimeMillis()
            val idx = ids.indexOf(actionId)
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (idx >= 0) {
                        val f = Finger(xs[idx], ys[idx])
                        f.trail += Pt(xs[idx], ys[idx], now)
                        held[actionId] = f
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in ids.indices) {
                        val f = held.getOrPut(ids[i]) { Finger(xs[i], ys[i]) }
                        f.x = xs[i]; f.y = ys[i]
                        f.trail += Pt(xs[i], ys[i], now)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val f = held.remove(actionId)
                    val x = f?.x ?: if (idx >= 0) xs[idx] else return
                    val y = f?.y ?: if (idx >= 0) ys[idx] else return
                    fades += Fade(actionId, x, y, now)
                    if (f != null && f.trail.size > 1) oldTrails += actionId to f.trail
                }
                MotionEvent.ACTION_CANCEL -> {
                    held.forEach { (id, f) ->
                        fades += Fade(id, f.x, f.y, now)
                        if (f.trail.size > 1) oldTrails += id to f.trail
                    }
                    held.clear()
                }
            }
            postInvalidateOnAnimation()
        }

        private fun drawTrail(c: Canvas, id: Int, trail: ArrayList<Pt>, now: Long) {
            while (trail.isNotEmpty() && now - trail[0].t > PATH_FADE_MS) trail.removeAt(0)
            if (trail.size < 2) return
            paint.strokeWidth = 5f * dp
            for (k in 1 until trail.size) {
                val a = trail[k - 1]
                val b = trail[k]
                val alpha = (255 * (1f - (now - b.t) / PATH_FADE_MS.toFloat())).toInt().coerceIn(0, 255)
                paint.color = color(id)
                paint.alpha = alpha
                c.drawLine(a.x, a.y, b.x, b.y, paint)
            }
        }

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val now = SystemClock.uptimeMillis()

            held.forEach { (id, f) -> drawTrail(canvas, id, f.trail, now) }
            val ot = oldTrails.iterator()
            while (ot.hasNext()) {
                val (id, trail) = ot.next()
                drawTrail(canvas, id, trail, now)
                if (trail.size < 2) ot.remove()
            }

            paint.strokeWidth = 5f * dp
            held.forEach { (id, f) ->
                paint.color = color(id)
                paint.alpha = 255
                canvas.drawCircle(f.x, f.y, radius, paint)
            }
            val it = fades.iterator()
            while (it.hasNext()) {
                val r = it.next()
                val t = now - r.start
                if (t > CIRCLE_FADE_MS) {
                    it.remove()
                    continue
                }
                paint.color = color(r.id)
                paint.alpha = 255 - (t * 255 / CIRCLE_FADE_MS).toInt()
                canvas.drawCircle(r.x, r.y, radius, paint)
            }
            if (held.isNotEmpty() || fades.isNotEmpty() || oldTrails.isNotEmpty()) postInvalidateOnAnimation()
        }

        private companion object {
            const val PATH_FADE_MS = 200L
            const val CIRCLE_FADE_MS = 150L
            // Mesmas 10 cores do Android-Show-Taps.
            val COLORS = intArrayOf(
                0xFF8B00FF.toInt(), 0xFF00FF00.toInt(), 0xFFFFA500.toInt(), 0xFFFF0000.toInt(),
                0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFF00BFFF.toInt(), 0xFFFF00FF.toInt(),
                0xFFFF6666.toInt(), 0xFFFFB266.toInt()
            )
        }
    }
}
