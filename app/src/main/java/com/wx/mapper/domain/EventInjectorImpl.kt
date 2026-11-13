package com.wx.mapper.domain

import android.view.InputEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import com.wx.mapper.model.EventInjector
import com.wx.mapper.model.EventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class EventInjectorImpl(private val eventManager: EventManager = EventManagerImpl()) :
    EventInjector {
    private val animators = mutableMapOf<Int, ObjectRandomAnimator>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // IMPORTANTE: fila ilimitada + trySend() chamado direto na thread de quem gerou o evento.
    // Antes cada evento fazia `scope.launch { queue.send {...} }` em Dispatchers.Default (várias
    // threads), então DOWN / MOVE / UP podiam chegar ao sistema FORA DE ORDEM. Isso quebrava o
    // WASD (o MOVE chegava antes do DOWN e o joystick não saía do lugar) e fazia as teclas
    // "travarem" ou soltarem sozinhas. Com trySend a ordem de chamada é preservada.
    private val taskQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    abstract fun inject(event: InputEvent)

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

    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) {
        animators[pointerID]?.let {
            it.cancel()
            animators.remove(pointerID)
        }
        val animator = ObjectRandomAnimator()
        animator.init(o1, o2, duration = 80)

        // `cancelled` é lido pela thread da fila; evita enviar MOVEs de um gesto já cancelado
        // sem descartar o último frame (isRunning já é false quando o último frame roda).
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        animator.doOnCancel { cancelled.set(true) }

        animator.doOnStart {
            // DOWN entra na fila ANTES de qualquer MOVE
            enqueue {
                if (!injected(pointerID)) {
                    downNow(pointerID, o1.x, o1.y)
                }
            }
        }
        animator.addUpdateListener { animation ->
            val offset = animation.animatedValue as Offset
            enqueue {
                if (cancelled.get()) return@enqueue
                if (eventManager.updatePointer(pointerID, offset.x, offset.y)) {
                    injectMotion(MotionEvent.ACTION_MOVE)
                }
            }
        }
        animator.doOnEnd {
            if (animators[pointerID] === animator) animators.remove(pointerID)
        }
        animators[pointerID] = animator
        animator.start()
    }

    override fun transFormGesture(pointerID: Int, position: Offset) {
        enqueue {
            eventManager.getPointerLocation(pointerID)?.let {
                withContext(Dispatchers.Main) {
                    injectGesture(pointerID, it, position)
                }
            }
        }
    }

    override fun releaseGesture(pointerID: Int) {
        animators[pointerID]?.let {
            it.cancel()
            animators.remove(pointerID)
        }
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

    override fun releasePointer(pointerID: Int) {
        enqueue {
            val action = eventManager.getPointerAction(pointerID, true)
            if (action == -1) return@enqueue
            injectMotion(action)
            eventManager.removePointer(pointerID)
        }
    }

    override fun clear() {
        animators.values.forEach { it.cancel() }
        animators.clear()
        enqueue {
            injectMotion(MotionEvent.ACTION_CANCEL)
            eventManager.clear()
        }
    }

    override suspend fun injected(pointerID: Int): Boolean {
        return eventManager.getPointerAction(pointerID, false) != -1
    }

}
