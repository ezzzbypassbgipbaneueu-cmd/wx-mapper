package com.wx.mapper.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartilhado entre o serviço de acessibilidade e o overlay (mesmo processo). */
object TypingState {
    /** true quando algum app (que não seja o próprio mapper) tem um campo de texto focado. */
    val editableFocused = MutableStateFlow(false)

    /** true enquanto o serviço de detecção estiver ativado nas configurações de acessibilidade. */
    val serviceConnected = MutableStateFlow(false)
}

/**
 * Só observa em qual momento um campo de texto ganha / perde o foco. Não lê o conteúdo digitado,
 * não clica e não altera nada: serve apenas para ligar o "modo digitar" sozinho.
 */
class TypingDetectorService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        TypingState.serviceConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = event.source
                TypingState.editableFocused.value = node?.isEditable == true
            }
            // Tocar em qualquer coisa que NÃO seja um campo de texto = acabou de digitar.
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                if (node?.isEditable != true) TypingState.editableFocused.value = false
            }
            // Troca de tela / janela / diálogo.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                TypingState.editableFocused.value = false
            }
        }
    }

    override fun onInterrupt() {
        TypingState.editableFocused.value = false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        TypingState.serviceConnected.value = false
        TypingState.editableFocused.value = false
        return super.onUnbind(intent)
    }
}
