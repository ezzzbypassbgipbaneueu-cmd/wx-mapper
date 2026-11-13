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
    private val taskQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
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
            // Enqueue synchronously from the animation callback. Launching a new
            // coroutine for every frame can reorder DOWN/MOVE/UP under load.
            taskQueue.trySend {
                if (!animator.isRunning) return@trySend
                eventManager.updatePointer(pointerID, offset.x, offset.y)
                val event = eventManager.createMotionEvent(MotionEvent.ACTION_MOVE)
                event?.let {
                    inject(it)
                    it.recycle()
                }
            }
        }
        animator.doOnEnd {
            animators.remove(pointerID)
        }
        animator.doOnStart {
            // Put DOWN into the same FIFO before the first animation MOVE.
            taskQueue.trySend {
                if (eventManager.getPointerAction(pointerID, false) == -1) {
                    eventManager.addPointer(pointerID, o1.x, o1.y)
                    val action = eventManager.getPointerAction(pointerID, false)
                    val event = eventManager.createMotionEvent(action)
                    event?.let {
                        inject(it)
                        it.recycle()
                    }
                }
            }
        }
        animator.start()
        animators[pointerID] = animator
    }

    override fun transFormGesture(pointerID: Int, position: Offset) {
        taskQueue.trySend {
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
        // Always tap the center of the mapped target. Random coordinates made
        // small controls unreliable and caused clicks to land around the target.
        val x = (o1.x + o2.x) / 2f
        val y = (o1.y + o2.y) / 2f
        addEventDown(pointerID, x, y)
    }

    override fun injectPointer(pointerID: Int, position: Offset) {
        addEventDown(pointerID, position.x, position.y)
    }

    override fun updatePointerPosition(pointerID: Int, position: Offset) {
        taskQueue.trySend {
            eventManager.offsetPointer(pointerID, position)
            val event = eventManager.createMotionEvent(MotionEvent.ACTION_MOVE)
            event?.let {
                inject(it)
                it.recycle()
            }
        }
    }


    private fun addEventDown(pointerID: Int, x: Float, y: Float) {
        taskQueue.trySend {
            val previousAction = eventManager.getPointerAction(pointerID, false)
            val index = eventManager.addPointer(pointerID, x, y)
            // Existing pointers are moved, not pressed a second time.
            val action = if (previousAction != -1 || index == -2) {
                MotionEvent.ACTION_MOVE
            } else {
                eventManager.getPointerAction(pointerID, false)
            }
            val event = eventManager.createMotionEvent(action)
            event?.let {
                inject(it)
                it.recycle()
            }
        }
    }

    override fun releasePointer(pointerID: Int) {
        taskQueue.trySend {
            val action = eventManager.getPointerAction(pointerID, true)
            if (action == -1) return@trySend
            val event = eventManager.createMotionEvent(action)
            event?.let {
                inject(it)
                it.recycle()
            }
            eventManager.removePointer(pointerID)
        }
    }

    override fun clear() {
        taskQueue.trySend {
            val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
            event?.let {
                inject(it)
                it.recycle()
            }
            eventManager.clear()
        }
    }

    override suspend fun injected(pointerID: Int): Boolean {
        return eventManager.getPointerAction(pointerID, false) != -1
    }

}