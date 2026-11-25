#!/usr/bin/env bash
# Teste de fumaça: instala o APK no emulador, abre cada tela e falha se houver crash.
set -u
APK=$(ls out/*.apk | head -1)
PKG=br.com.carrinhocerto
mkdir -p shots
adb root || true
sleep 3
adb wait-for-device
adb install -r -g "$APK"
adb shell pm grant $PKG android.permission.CAMERA || true
adb logcat -c
shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; }
adb shell am start -W -n $PKG/.ui.MainActivity;    shot 1_carrinho 4
adb shell am start -W -n $PKG/.ui.ItemActivity;    shot 2_item 4
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -W -n $PKG/.ui.HistoryActivity; shot 3_historico 4
adb shell am start -W -n $PKG/.ui.ScanActivity;    shot 4_scan 8
adb logcat -d > shots/logcat.txt
if grep -q "FATAL EXCEPTION" shots/logcat.txt; then
  grep -A 30 "FATAL EXCEPTION" shots/logcat.txt
  exit 1
fi
echo "Nenhum crash."
