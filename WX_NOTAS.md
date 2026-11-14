# WX MAPPER

Package `com.wx.mapper`.

## O que foi adicionado (código real, ligado ao injetor Shizuku já existente)
- `autoclicker/` — AutoClicker (CPS 1–200, padrão 30; gatilho padrão: botão direito; segura = clica, solta = para).
- `macros/` — Macro, MacroAction (Tap / Wait), MacroPlayer (loop, parada segura).
- `input/WxActions` — liga teclas/botões do mouse ao autoclicker e às macros; rede de segurança via `buttonState`.
- `input/WxRuntime` — ponte serviço de overlay ⇄ telas.
- Telas: Autoclicker, Macros (acessíveis pela barra inferior da tela principal). Tema roxo/azul + GlassCard.
- Persistência: `AppConfig.autoClicker` e `AppConfig.macros` no DataStore (JSON).
- Testes: `app/src/test/.../wx/WxTests.kt`.

## Limitações (honestas)
- Autoclicker e macros só atuam com o overlay rodando (jogo aberto pelo launcher) e Shizuku ativo.
- Posição do clique fixa é digitada em pixels (sem seletor visual).
- Macros: só Toque e Esperar. Gravação ao vivo, importar/exportar e telas Dashboard/Perfis/Dispositivos dedicadas NÃO foram feitas.
- Código não foi compilado no ambiente em que foi escrito (sem Android SDK/rede).

## Build
`./gradlew testDebugUnitTest assembleDebug` → app/build/outputs/apk/debug/app-debug.apk
Ou suba para o GitHub: o workflow `.github/workflows/release-apk.yml` gera o APK debug.
