# WX Mapper - hotfix parcial

Correções incluídas:
- WASD agora calcula posição para qualquer combinação simultânea de W/A/S/D, incluindo três e quatro teclas. Antes só existiam destinos cadastrados para teclas isoladas e alguns pares.
- após perda de foco do overlay durante o jogo, o serviço limpa os toques virtuais que poderiam ficar presos e tenta recuperar automaticamente o foco e a captura do ponteiro.

IMPORTANTE:
Este hotfix não implementa ainda o seletor de PNG do cursor, o modo de digitação automática,
o modo de ocultar tudo durante gravação, nem a escala visual dos botões. Também não é uma
garantia de que todos os problemas de foco/teclado foram resolvidos. O projeto precisa ser
compilado e testado no dispositivo antes de substituir o APK atual.
