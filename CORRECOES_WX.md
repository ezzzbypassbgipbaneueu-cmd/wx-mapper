# WX Mapper — correções de teclado, cursor e mapeamento

Alterações nesta versão:

- O overlay de mapeamento só captura teclas para editar quando está expandido.
- Quando o overlay está recolhido, o ComposeView recupera o foco e encaminha teclas físicas para o EventHandler.
- Os campos de mapeamento deixam de permanecer focáveis durante o jogo, evitando que roubem o foco do teclado.
- O movimento do mouse usa AXIS_RELATIVE_X / AXIS_RELATIVE_Y, em vez de acumular rawX/rawY absolutos.
- A sensibilidade é lida em tempo real.
- O deslocamento injetado é limitado ao movimento efetivamente aplicado depois de limitar o cursor às bordas da tela.
- Os estados das teclas físicas e dos ponteiros mapeados foram separados para evitar colisões de índices.
- Eventos de foco perdido liberam os ponteiros virtuais ativos.
- Foram adicionadas opções de cursor: seta, mira, círculo e ponto; tamanho de 12 a 64 dp; seis cores; opacidade.
- As opções do cursor são armazenadas no AppConfig/DataStore e possuem valores padrão para configurações antigas.

## Validação

O projeto foi verificado estaticamente. Não foi possível executar a compilação Gradle neste ambiente porque o wrapper precisa baixar Gradle 8.9 de services.gradle.org e o ambiente de execução não tem acesso à rede.

Depois de enviar esta versão ao GitHub, execute o workflow WX MAPPER APK. Se a compilação falhar, envie o log completo para corrigirmos o erro real.
