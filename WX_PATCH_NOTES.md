# Wx Mapper patch notes

This patch is a first implementation pass, not a finished release.

## Included
- Ignore stale/duplicate physical key-up events so an old UP cannot release a pointer belonging to a newer press.
- Clear the shooting-mode flow consistently when all virtual input is cleared.
- Use WindowManager display bounds on Android 11+ for cursor movement limits, with resource metrics as a fallback on older Android.
- Delay focus-loss cleanup until the overlay is still unfocused on the next UI-loop turn. This avoids clearing active key states on transient focus changes during pointer capture.
- Reduce mapped control visual scale by 25% without changing saved positions.
- Setting overlay opacity to 0 now hides the cursor and mapped controls together, providing a clean recording screen.

## Not yet implemented
- Selecting a custom PNG cursor and persisting its URI.
- Automatic typing/focus detection for arbitrary third-party text fields. Android does not expose a universal reliable signal for this without an Accessibility Service and user-granted access.
- Exact click-hotspot calibration. This needs an on-device coordinate test because display insets/rotation and Shizuku injection coordinates must be measured on the target device.

## Validation
The source changes have not been compiled on an Android SDK in this environment. Run the GitHub Actions build before installing.

## Modo digitar (automático e manual)
- TypingDetectorService: ignora eventos do teclado virtual, confere o foco real (FOCUS_INPUT) com atraso antes de desligar e mantém ligado enquanto chega texto.
- FloatingBubbleService: recaptura de ponteiro agendada não roda mais se o modo digitar estiver ativo.

## Autoclicker (precisão)
- Worker em thread própria de prioridade alta, cronograma absoluto (CPS fiel, sem acumular atraso).
- Retomar ao soltar o botão esquerdo agora acorda o worker na hora (antes: até 8 ms de poll).
- Tempo do toque: teto de 50 ms, padrão 12 ms, e o toque não é mais limitado a metade do ciclo (só deixa 3 ms de respiro).
