# WX Mapper - ajustes desta entrega

1. Descongelar ao sair do jogo: agora há 2 detecções (eventos de acessibilidade + vigia via Shizuku a cada ~7 s que vê a activity no topo, inclusive o próprio WX), tentativa repetida se falhar e botão "Descongelar apps" na notificação.
2. Modo gravação: notificação mostra "● MODO GRAVAÇÃO LIGADO", vibra 1x (liga) / 2x (desliga) e, nos modos SKIP_SCREENSHOT/FLAG_SECURE, aparece o selo "● REC" (fora da gravação).
3. Sensibilidade: slider logarítmico de 1% a 300% (antes 5%-300% linear). Valores salvos continuam valendo.
4. Otimização: GameOptimizer (Processos > Otimizar sozinho ao abrir o jogo). Restaura animações, não limpa cache, compila 1x por versão ao sair.
5. Cursor: posição escrita direto no State do Compose, entrega de mouse sem agrupar (requestUnbufferedDispatch) e thread principal com prioridade de tela.

Não foi possível compilar/testar neste ambiente (sem Gradle/rede).
