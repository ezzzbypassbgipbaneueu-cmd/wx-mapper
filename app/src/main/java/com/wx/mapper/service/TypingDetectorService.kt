package com.wx.mapper.service

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartilhado entre o serviço de acessibilidade e o overlay (mesmo processo). */
object TypingState {
    /** true quando algum app (que não seja o próprio mapper) tem um campo de texto focado. */
    val editableFocused = MutableStateFlow(false)

    /** true enquanto o serviço de detecção estiver ativado nas configurações de acessibilidade. */
    val serviceConnected = MutableStateFlow(false)

    /** Modo desempenho: o detector só escuta troca de janela (o digitar automático fica desligado). */
    val lightMode = MutableStateFlow(false)

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

    private var failCount = 0
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lightJob: kotlinx.coroutines.Job? = null

    /** true se a janela do teclado virtual está na tela agora. */
    private fun imeVisible(): Boolean = try {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (_: Exception) {
        false
    }

    private val recheck = Runnable {
        if (!TypingState.editableFocused.value) return@Runnable

        // Teclado na tela = a pessoa está digitando. Nunca derruba o modo digitar nesse caso
        // (era isso que fazia o teclado subir e sumir do campo de texto).
        if (imeVisible()) {
            failCount = 0
            scheduleRecheck(1500L)
            return@Runnable
        }

        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        val pkg = root?.packageName?.toString()
        val stillFocused = if (root != null && pkg != null && pkg != packageName && !isIme(pkg)) {
            try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true } catch (_: Exception) { false }
        } else {
            false
        }

        if (stillFocused) {
            failCount = 0
            scheduleRecheck(1500L)
        } else if (++failCount >= 3) {
            // 3 conferências seguidas sem teclado e sem campo focado: aí sim solta.
            failCount = 0
            TypingState.editableFocused.value = false
        } else {
            scheduleRecheck(700L)
        }
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
        failCount = 0
        TypingState.editableFocused.value = true
        scheduleRecheck(1500L) // confirma que o foco continua num campo de texto
    }

    private fun applyEventTypes(light: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (light) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        } else {
            AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        }
        serviceInfo = info
        if (light) {
            handler.removeCallbacks(recheck)
            TypingState.editableFocused.value = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshImes()
        TypingState.serviceConnected.value = true
        // Menos eventos = menos trabalho no jogo e no sistema.
        lightJob?.cancel()
        lightJob = uiScope.launch { TypingState.lightMode.collect { applyEventTypes(it) } }
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
                // Está chegando texto no campo realmente focado = ainda digitando.
                val src = event.source
                if (src?.isEditable == true && src.isFocused) setEditable()
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
        lightJob?.cancel()
        TypingState.serviceConnected.value = false
        TypingState.editableFocused.value = false
        return super.onUnbind(intent)
    }
}
