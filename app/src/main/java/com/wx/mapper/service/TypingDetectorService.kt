package com.wx.mapper.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartilhado entre o serviço de acessibilidade e o overlay (mesmo processo). */
object TypingState {
    /** true quando algum app (que não seja o próprio mapper) tem um campo de texto focado. */
    val editableFocused = MutableStateFlow(false)

    /** true enquanto o serviço de detecção estiver ativado nas configurações de acessibilidade. */
    val serviceConnected = MutableStateFlow(false)

    /**
     * Último app (que não é o próprio mapper nem um teclado) que abriu uma janela. O overlay usa
     * isso para trocar sozinho para o perfil do jogo que você abriu.
     */
    val foregroundPackage = MutableStateFlow<String?>(null)
}

/**
 * Só observa em qual momento um campo de texto ganha / perde o foco. Não lê o conteúdo digitado,
 * não clica e não altera nada: serve apenas para ligar o "modo digitar" sozinho.
 *
 * Regras importantes:
 *  - Eventos do TECLADO (Gboard, Samsung...) são ignorados: abrir o teclado gera
 *    WINDOW_STATE_CHANGED e antes isso desligava o modo digitar logo depois de ligar.
 *  - Nunca desliga "no escuro": espera um instante e confere o foco real (FOCUS_INPUT).
 *  - Se chegar texto sendo digitado, é porque ainda está digitando: mantém ligado.
 */
class TypingDetectorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var imePackages: Set<String> = emptySet()

    private val recheck = Runnable {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        val pkg = root?.packageName?.toString()
        // Sem janela ativa, janela do teclado ou do próprio mapper: não dá para concluir nada, mantém.
        if (root == null || pkg == null || pkg == packageName || isIme(pkg)) return@Runnable
        val focused = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        TypingState.editableFocused.value = focused?.isEditable == true
    }

    private fun isIme(pkg: String?): Boolean = pkg != null && pkg in imePackages

    private fun refreshImes() {
        imePackages = try {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList?.map { it.packageName }?.toSet().orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun scheduleRecheck(delayMs: Long = 350L) {
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, delayMs)
    }

    private fun setEditable() {
        handler.removeCallbacks(recheck)
        TypingState.editableFocused.value = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshImes()
        TypingState.serviceConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        // Teclado virtual: nunca decide nada (nem foco, nem pacote em primeiro plano).
        if (pkg != null && imePackages.isEmpty()) refreshImes()
        if (isIme(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (event.source?.isEditable == true) setEditable() else scheduleRecheck()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Está chegando texto = ainda digitando.
                if (event.source?.isEditable == true) setEditable()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (event.source?.isEditable == true) setEditable() else scheduleRecheck()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                pkg?.let { TypingState.foregroundPackage.value = it }
                scheduleRecheck(500L)
            }
        }
    }

    override fun onInterrupt() {
        // Não desliga aqui: interrupções são temporárias; o recheck confere o foco real.
        scheduleRecheck(500L)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(recheck)
        TypingState.serviceConnected.value = false
        TypingState.editableFocused.value = false
        return super.onUnbind(intent)
    }
}
