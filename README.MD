# WX MAPPER

App Android para jogar com teclado e mouse externos: mapeia teclas e botões do mouse para toques na tela, com autoclicker e macros.

## Recursos

- Mapeamento de teclado e mouse para pontos da tela (botões arrastáveis por jogo)
- Cursor do mouse com estilo, tamanho e cor configuráveis
- Autoclicker (segurar o botão direito para clicar, soltar para parar), com CPS e tempo do toque ajustáveis
- Macros: grave com **F8** (aperte F8, faça os cliques/teclas, aperte F8 de novo) ou monte na tela de Macros
- Backup das configurações em arquivo `.json` (Settings → Exportar / Importar)

## Requisitos

- Android 10 (API 29) ou superior
- [Shizuku](https://shizuku.rikka.app/) instalado e em execução (sem ele o app não funciona)
- Teclado e mouse externos

## Como usar

1. Instale o APK e abra o WX MAPPER.
2. Conceda a permissão do Shizuku quando o app pedir.
3. Adicione o jogo, abra o overlay e posicione os botões onde quer que os toques aconteçam.
4. Toque na bolha para fechar o editor e começar a jogar.

## Compilar

```bash
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/`. Para rodar os testes: `./gradlew testDebugUnitTest`.

## Licença

Veja o arquivo [LICENSE](LICENSE).
