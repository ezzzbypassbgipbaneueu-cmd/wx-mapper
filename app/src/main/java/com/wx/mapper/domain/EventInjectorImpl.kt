package com.wx.mapper.domain

import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.wx.mapper.model.EventInjector
import com.wx.mapper.model.EventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

abstract class EventInjectorImpl(private val eventManager: EventManager = EventManagerImpl()) :
    EventInjector {
    // Gestos (joystick WASD): cada ponteiro tem um job que empurra o dedo do centro até a borda.
    // Antes isso usava ValueAnimator (preso ao Choreographer da thread principal); agora roda
    // direto na fila de eventos, sem depender de frame de tela.
    private val gestureJobs = ConcurrentHashMap<Int, Job>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // IMPORTANTE: fila ilimitada + trySend() chamado direto na thread de quem gerou o evento.
    // Antes cada evento fazia `scope.launch { queue.send {...} }` em Dispatchers.Default (várias
    // threads), então DOWN / MOVE / UP podiam chegar ao sistema FORA DE ORDEM. Isso quebrava o
    // WASD (o MOVE chegava antes do DOWN e o joystick não saía do lugar) e fazia as teclas
    // "travarem" ou soltarem sozinhas. Com trySend a ordem de chamada é preservada.
    private val taskQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    abstract fun inject(event: InputEvent)

    /** Injeção sem esperar resposta do sistema (menor latência). Por padrão igual a [inject]. */
    protected open fun injectFast(event: InputEvent) = inject(event)

    init {
        scope.launch {
            for (task in taskQueue) {
                try {
                    task.invoke()
                } catch (_: Throwable) {
                    // um evento ruim não pode matar a fila inteira
                }
            }
        }
    }

    private fun enqueue(task: suspend () -> Unit) {
        taskQueue.trySend(task)
    }

    private suspend fun injectMotion(action: Int) {
        val event = eventManager.createMotionEvent(action)
        event?.let {
            inject(it)
            it.recycle()
        }
    }

    private suspend fun downNow(pointerID: Int, x: Float, y: Float) {
        eventManager.addPointer(pointerID, x, y)
        val action = eventManager.getPointerAction(pointerID, false)
        if (action == -1) return
        injectMotion(action)
    }

    private fun startMove(pointerID: Int, from: Offset, to: Offset) {
        gestureJobs.remove(pointerID)?.cancel()
        val job = scope.launch {
            for (i in 1..GESTURE_STEPS) {
                delay(GESTURE_STEP_MS)
                ensureActive()
                val f = i / GESTURE_STEPS.toFloat()
                val jitter = if (i < GESTURE_STEPS) (-1..1).random().toFloat() else 0f
                val x = from.x + (to.x - from.x) * f + jitter
                val y = from.y + (to.y - from.y) * f - jitter
                enqueue {
                    if (eventManager.updatePointer(pointerID, x, y)) {
                        injectMotion(MotionEvent.ACTION_MOVE)
                    }
                }
            }
        }
        gestureJobs[pointerID] = job
    }

    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) {
        gestureJobs.remove(pointerID)?.cancel()
        // DOWN entra na fila ANTES de qualquer MOVE
        enqueue {
            if (!injected(pointerID)) {
                downNow(pointerID, o1.x, o1.y)
            }
        }
        startMove(pointerID, o1, o2)
    }

    override fun transFormGesture(pointerID: Int, position: Offset) {
        gestureJobs.remove(pointerID)?.cancel()
        enqueue {
            eventManager.getPointerLocation(pointerID)?.let {
                startMove(pointerID, it, position)
            }
        }
    }

    override fun releaseGesture(pointerID: Int) {
        gestureJobs.remove(pointerID)?.cancel()
        releasePointer(pointerID)
    }

    override fun injectPointer(pointerID: Int, o1: Offset, o2: Offset) {
        val x1 = minOf(o1.x, o2.x).toInt()
        val x2 = maxOf(o1.x, o2.x).toInt()
        val y1 = minOf(o1.y, o2.y).toInt()
        val y2 = maxOf(o1.y, o2.y).toInt()
        val x = (x1..x2).random().toFloat()
        val y = (y1..y2).random().toFloat()
        enqueue { downNow(pointerID, x, y) }
    }

    override fun injectPointer(pointerID: Int, position: Offset) {
        enqueue { downNow(pointerID, position.x, position.y) }
    }

    override fun updatePointerPosition(pointerID: Int, position: Offset) {
        enqueue {
            if (eventManager.offsetPointer(pointerID, position)) {
                injectMotion(MotionEvent.ACTION_MOVE)
            }
        }
    }

    /**
     * Toque de duração zero: DOWN e UP saem na MESMA tarefa da fila, com o mesmo timestamp e sem
     * esperar o sistema responder entre eles. A duração real vira só o tempo de entregar os 2 eventos.
     */
    override fun tap(pointerID: Int, position: Offset) {
        enqueue {
            val t = SystemClock.uptimeMillis()
            eventManager.addPointer(pointerID, position.x, position.y)
            val down = eventManager.getPointerAction(pointerID, false)
            if (down == -1) return@enqueue
            eventManager.createMotionEvent(down, t)?.let {
                injectFast(it)
                it.recycle()
            }
            val up = eventManager.getPointerAction(pointerID, true)
            if (up != -1) {
                eventManager.createMotionEvent(up, t)?.let {
                    injectFast(it)
                    it.recycle()
                }
                eventManager.removePointer(pointerID)
            }
        }
    }

    override fun releasePointer(pointerID: Int) {
        enqueue {
            val action = eventManager.getPointerAction(pointerID, true)
            if (action == -1) return@enqueue
            injectMotion(action)
            eventManager.removePointer(pointerID)
        }
    }

    override fun clear() {
        gestureJobs.values.forEach { it.cancel() }
        gestureJobs.clear()
        enqueue {
            injectMotion(MotionEvent.ACTION_CANCEL)
            eventManager.clear()
        }
    }

    override suspend fun injected(pointerID: Int): Boolean {
        return eventManager.getPointerAction(pointerID, false) != -1
    }


    private companion object {
        const val GESTURE_STEPS = 6
        const val GESTURE_STEP_MS = 12L
    }
}
