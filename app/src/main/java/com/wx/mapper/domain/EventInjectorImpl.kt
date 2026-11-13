package com.wx.mapper.domain

import android.view.InputEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
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
    private val taskQueue = Channel<suspend ()->Unit>()
    abstract fun inject(event: InputEvent)

    init {
        scope.launch {
            for (task in taskQueue){
                task.invoke()
            }
        }
    }
    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) {

        animators[pointerID]?.let {
            it.cancel()
            animators.remove(pointerID)
        }
        val animator = ObjectRandomAnimator()
        animator.init(o1, o2, duration = 80)
        animator.addUpdateListener { animation ->
            val offset = animation.animatedValue as Offset
            scope.launch {
                taskQueue.send {
                    if (!animator.isRunning)
                        return@send
                    eventManager.updatePointer(pointerID, offset.x, offset.y)
                    val event = eventManager.createMotionEvent(MotionEvent.ACTION_MOVE)
                    event?.let {
                        inject(it)
                        it.recycle()
                    }
                }
            }
        }
        animator.doOnEnd {
            animators.remove(pointerID)
        }
        animator.doOnStart {//inject action down once at $o1 if not injected
            scope.launch {
                if (!injected(pointerID)) {
                    addEventDown(pointerID, o1.x, o1.y)
                }
            }
        }
        animator.start()
        animators[pointerID] = animator
    }

    override fun transFormGesture(pointerID: Int, position: Offset) {
        scope.launch {
            taskQueue.send {
                eventManager.getPointerLocation(pointerID)?.let {
                    withContext(Dispatchers.Main){
                        injectGesture(pointerID,it,position)
                    }
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
        // Mapeamentos representam um alvo, não uma área aleatória. Usar o centro
        // evita que o toque varie a cada acionamento e melhora a precisão.
        val x = (o1.x + o2.x) / 2f
        val y = (o1.y + o2.y) / 2f
        addEventDown(pointerID, x, y)
    }

    override fun injectPointer(pointerID: Int, position: Offset) {
        addEventDown(pointerID, position.x, position.y)
    }

    override fun updatePointerPosition(pointerID: Int, position: Offset) {
        scope.launch {
            taskQueue.send {
                eventManager.offsetPointer(pointerID, position)
                val event = eventManager.createMotionEvent(MotionEvent.ACTION_MOVE)
                event?.let {
                    inject(it)
                    it.recycle()
                }
            }
        }
    }


    private fun addEventDown(pointerID: Int, x: Float, y: Float) {
        scope.launch {
            taskQueue.send {
                eventManager.addPointer(pointerID, x, y)
                val action = eventManager.getPointerAction(pointerID, false)
                val event = eventManager.createMotionEvent(action)
                event?.let {
                    inject(it)
                    it.recycle()
                }
            }
        }
    }

    override fun releasePointer(pointerID: Int) {
        scope.launch {
            taskQueue.send {
                val action = eventManager.getPointerAction(pointerID, true)
                if (action == -1)
                    return@send
                val event = eventManager.createMotionEvent(action)
                event?.let {
                    inject(it)
                    it.recycle()
                }
                eventManager.removePointer(pointerID)
            }
        }
    }

    override fun clear() {
        scope.launch {
            taskQueue.send {
                val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
                event?.let {
                    inject(it)
                    it.recycle()
                }
                eventManager.clear()
            }
        }
    }

    override suspend fun injected(pointerID: Int): Boolean {
        return eventManager.getPointerAction(pointerID, false) != -1
    }

}