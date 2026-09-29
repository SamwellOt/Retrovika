#!/usr/bin/env bash
# Instala o APK, abre o app, espera alguns segundos e falha se o processo morreu ou houve FATAL EXCEPTION.
# Uso: startup-smoke.sh <apk> <pasta de saída>
set -u
APK="$1"
OUT="$2"
PKG=com.retrovika.app
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 20
adb exec-out screencap -p > "$OUT/screen.png" || true
adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -b crash > "$OUT/crash.txt" || true
echo "===== crash buffer ====="
cat "$OUT/crash.txt"
echo "===== AndroidRuntime ====="
grep -A 60 -E "FATAL EXCEPTION|AndroidRuntime" "$OUT/logcat.txt" | head -200
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" || [ -s "$OUT/crash.txt" ] || ! adb shell pidof "$PKG" > /dev/null; then
  echo "O app fechou na abertura"
  exit 1
fi
echo "O app continua aberto"
