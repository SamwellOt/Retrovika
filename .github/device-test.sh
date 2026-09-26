#!/usr/bin/env bash
# Teste no emulador: instala o APK de debug, coloca ROMs homebrew livres na biblioteca,
# abre cada jogo e guarda logcat + capturas em device-out/.
set -x
PKG=com.retrovika.app
OUT=device-out
mkdir -p "$OUT"
adb root || true
sleep 3
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Primeira abertura cria as pastas do app.
adb shell am start -W -n $PKG/.MainActivity
sleep 12
adb shell am force-stop $PKG

ROMS=/sdcard/Android/data/$PKG/files/Retrovika/roms
adb shell mkdir -p $ROMS/n64
for f in roms-test/n64/*; do adb push "$f" $ROMS/n64/; done
adb shell ls -la $ROMS/n64

# Segunda abertura: a varredura registra os jogos.
adb shell am start -W -n $PKG/.MainActivity
sleep 20
adb exec-out screencap -p > "$OUT/home.png"
adb shell "sqlite3 /data/data/$PKG/databases/retrovika.db 'select id, systemId, fileName from games'" | tee "$OUT/games.txt"

for id in $(cut -d'|' -f1 "$OUT/games.txt"); do
  adb logcat -c
  adb shell am start -n $PKG/.emulation.GameActivity --el game_id "$id"
  # Núcleo baixado do buildbot na primeira vez: dá tempo para baixar e rodar.
  for t in 20 40 70; do
    sleep 20
    adb exec-out screencap -p > "$OUT/game-$id-${t}s.png"
  done
  adb shell pidof $PKG > "$OUT/game-$id-pid.txt" || echo "PROCESSO MORTO" > "$OUT/game-$id-pid.txt"
  adb logcat -d > "$OUT/game-$id-logcat.txt"
  grep -E "AndroidRuntime|FATAL|DEBUG|libc|signal|Abort|libretrodroid|LibretroDroid|mupen|Mupen|GLideN|flycast|Flycast|Retrovika|GameActivity|EGL|GLES|tombstone" \
    "$OUT/game-$id-logcat.txt" > "$OUT/game-$id-filtered.txt" || true
  echo "===== jogo $id: $(cat "$OUT/game-$id-pid.txt")"
  tail -n 150 "$OUT/game-$id-filtered.txt"
  adb shell am force-stop $PKG
  sleep 3
done
exit 0
