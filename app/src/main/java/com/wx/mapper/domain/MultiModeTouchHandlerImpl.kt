package com.wx.mapper.domain

import android.view.KeyEvent
import android.view.MotionEvent
import com.wx.mapper.model.EventInjector
import com.wx.mapper.model.KeyMap
import com.wx.mapper.model.MultiModeTouchHandler

class TapModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
            eventInjector.releasePointer(pointerId)
            return !isPressed
        }
        return isPressed
    }
}

class HoldModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        return if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
            true
        }else if (keyEvent.action == MotionEvent.ACTION_UP){
            eventInjector.releasePointer(pointerId)
            false
        }else
            isPressed
    }
}

class MixedModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    private var lastKeyDownMap = hashMapOf<Int, Long>()
    private val minDifference = 200
    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            lastKeyDownMap[pointerId] = System.currentTimeMillis()
            if (isPressed) {
                eventInjector.releasePointer(pointerId)
            } else {
                eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
            }
            return !isPressed
        } else if (keyEvent.action == MotionEvent.ACTION_UP) {
            if (!isPressed)
                return false
            val difference = System.currentTimeMillis() - (lastKeyDownMap[pointerId] ?: 0)
            if (difference > minDifference) {
//               eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
                eventInjector.releasePointer(pointerId)
                return false
            }
        }
        return isPressed
    }

}

/**
 * Tecla ALTERNAR: apertar a tecla uma vez SEGURA o toque no ponto escolhido; apertar de novo SOLTA.
 * Soltar a tecla física não faz nada (o toque continua pressionado).
 */
class ToggleModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (keyEvent.action != MotionEvent.ACTION_DOWN) return isPressed
        return if (isPressed) {
            eventInjector.releasePointer(pointerId)
            false
        } else {
            eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end ?: keyMap.position)
            true
        }
    }
}
