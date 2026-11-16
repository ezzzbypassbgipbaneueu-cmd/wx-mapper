#!/bin/sh
# Rode na raiz do repositório (onde fica a pasta app/), depois de extrair o zip aqui.
W=app/src/main/java/com/wx/mapper/input/WxActions.kt
T=app/src/test/java/com/wx/mapper/wx/WxTests.kt
sed -i 's/CoroutineScope(SupervisorJob() + Dispatchers.Default)/CoroutineScope(SupervisorJob() + WxHighPriorityDispatcher)/' "$W"
grep -q "autoclicker.WxHighPriorityDispatcher" "$W" || sed -i 's/^import com.wx.mapper.autoclicker.AutoClicker$/&\nimport com.wx.mapper.autoclicker.WxHighPriorityDispatcher/' "$W"
sed -i 's/val clicker = AutoClicker(backgroundScope, inj)$/val clicker = AutoClicker(backgroundScope, inj, clock = { testScheduler.currentTime * 1_000_000L })/' "$T"
grep -n "WxHighPriorityDispatcher" "$W"
grep -n "clock = " "$T"
