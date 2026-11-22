package com.wx.mapper.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
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
     * Visual do "Mostrar toques" nativo do Android: disco cinza-claro translúcido com borda suave,
     * ponto branco no centro, sem rastro, e que some rápido ao soltar.
     */
    private class TapView(ctx: Context) : View(ctx) {
        private val dp = ctx.resources.displayMetrics.density
        private val radius = 26f * dp
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * dp
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private class Finger(var x: Float, var y: Float)
        private class Fade(val x: Float, val y: Float, val start: Long)

        private val held = HashMap<Int, Finger>()
        private val fades = ArrayList<Fade>()
        private val loc = IntArray(2)

        fun update(action: Int, actionId: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
            val now = SystemClock.uptimeMillis()
            val idx = ids.indexOf(actionId)
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (idx >= 0) held[actionId] = Finger(xs[idx], ys[idx])
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in ids.indices) held.getOrPut(ids[i]) { Finger(xs[i], ys[i]) }.also { it.x = xs[i]; it.y = ys[i] }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val f = held.remove(actionId)
                    val x = f?.x ?: if (idx >= 0) xs[idx] else return
                    val y = f?.y ?: if (idx >= 0) ys[idx] else return
                    fades += Fade(x, y, now)
                }
                MotionEvent.ACTION_CANCEL -> {
                    held.values.forEach { fades += Fade(it.x, it.y, now) }
                    held.clear()
                }
            }
            postInvalidateOnAnimation()
        }

        private fun spot(c: Canvas, x: Float, y: Float, a: Float) {
            disc.shader = RadialGradient(
                x, y, radius,
                intArrayOf(argb(a, 0xB8, 255, 255, 255), argb(a, 0x99, 235, 235, 235), argb(a, 0x66, 200, 200, 200)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(x, y, radius, disc)
            edge.color = argb(a, 0x66, 150, 150, 150)
            c.drawCircle(x, y, radius - edge.strokeWidth / 2f, edge)
            dot.color = argb(a, 0xE6, 255, 255, 255)
            c.drawCircle(x, y, 3.5f * dp, dot)
        }

        private fun argb(a: Float, alpha: Int, r: Int, g: Int, b: Int) =
            android.graphics.Color.argb((alpha * a).toInt().coerceIn(0, 255), r, g, b)

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val now = SystemClock.uptimeMillis()
            held.values.forEach { spot(canvas, it.x, it.y, 1f) }
            val it = fades.iterator()
            while (it.hasNext()) {
                val f = it.next()
                val t = (now - f.start) / FADE_MS.toFloat()
                if (t >= 1f) { it.remove(); continue }
                spot(canvas, f.x, f.y, 1f - t)
            }
            if (held.isNotEmpty() || fades.isNotEmpty()) postInvalidateOnAnimation()
        }

        private companion object {
            const val FADE_MS = 220L
        }
    }
}
