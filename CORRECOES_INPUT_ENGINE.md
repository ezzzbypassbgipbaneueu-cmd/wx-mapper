# WX Mapper - correções do motor de entrada

## Alterações desta revisão

- O overlay agora usa um `FrameLayout` focável como raiz e intercepta `dispatchKeyEvent`, encaminhando DOWN/UP para o mapeador antes do Compose. O `OnKeyListener` anterior dependia do caminho de foco de uma view filha e podia deixar o teclado sem chegar ao motor.
- A fila de injeção agora é ilimitada e recebe tarefas de forma síncrona com `trySend`. Antes, cada DOWN/MOVE/UP era agendado em uma coroutine independente e podia chegar fora de ordem; isso podia descartar DOWN/UP ou deixar toques presos.
- O início do gesto WASD coloca o DOWN na mesma fila FIFO antes dos movimentos da animação.
- Quando o alvo já está pressionado, um novo ponto atualiza o ponteiro com MOVE em vez de emitir um segundo DOWN.
- Toques de área agora usam o centro geométrico do alvo em vez de sortear coordenadas diferentes a cada clique.

## Referência externa analisada

Foi analisado o XtMapper (Xtr126/XtMapper), projeto Android aberto focado em teclado e mouse para jogos, com editor visual e perfis. Esta revisão não copia código do XtMapper; mantém a arquitetura e o licenciamento do WX Mapper e corrige os problemas encontrados no próprio motor.

## Limitações de validação

A compilação local não pôde ser executada: o wrapper precisa baixar Gradle 8.9 de `services.gradle.org`, mas o ambiente está sem acesso à rede. Portanto, este ZIP contém alterações reais no código-fonte, mas ainda precisa de build e teste em dispositivo Android com Shizuku autorizado e teclado/mouse conectados.
