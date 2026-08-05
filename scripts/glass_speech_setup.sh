#!/usr/bin/env bash
# Points the glasses at Google's speech recognition + TTS engine, and verifies it stuck.
#
# Run this AFTER any visit to the Google TTS settings UI. RayNeo's Mercury launcher
# force-stops apps when they leave the foreground, and Android resets
# `voice_recognition_service` to the system default whenever the configured service's
# package enters the stopped state -- so opening the TTS settings screen and backing out
# silently reverts the recognizer to `com.rayneo.aispeech`.
#
# Usage: ./glass_speech_setup.sh

set -euo pipefail

GOOGLE_ASR="com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
GOOGLE_TTS_PKG="com.google.android.tts"
DEVICE_ARG=()
[[ -n "${DEVICE_SERIAL:-}" ]] && DEVICE_ARG=(-s "$DEVICE_SERIAL")

adb "${DEVICE_ARG[@]}" wait-for-device

if ! adb "${DEVICE_ARG[@]}" shell pm list packages | grep -q "$GOOGLE_TTS_PKG"; then
  echo "ERROR: $GOOGLE_TTS_PKG is not installed."
  echo "Install it first:"
  echo "  adb install -r -g '/Users/sukoon/Documents/Hackathon26/design_docs/Glasses/glass/android_asr_tts.apk'"
  exit 1
fi

echo "==> Setting Google as recognition + TTS engine"
adb "${DEVICE_ARG[@]}" shell settings put secure voice_recognition_service "$GOOGLE_ASR"
adb "${DEVICE_ARG[@]}" shell settings put secure tts_default_synth "$GOOGLE_TTS_PKG"
adb "${DEVICE_ARG[@]}" shell settings put secure enabled_tts_engines "$GOOGLE_TTS_PKG"

# Doze whitelist won't stop Mercury's own force-stop, but costs nothing and helps
# keep the service alive under standard Android background restrictions.
adb "${DEVICE_ARG[@]}" shell dumpsys deviceidle whitelist "+$GOOGLE_TTS_PKG" >/dev/null 2>&1 || true

echo "==> Verifying"
actual=$(adb "${DEVICE_ARG[@]}" shell settings get secure voice_recognition_service | tr -d '\r')
if [[ "$actual" == "$GOOGLE_ASR" ]]; then
  echo "    recognition service : OK (Google)"
else
  echo "    recognition service : REVERTED -> $actual"
  echo "    Something force-stopped $GOOGLE_TTS_PKG. Re-run this script and avoid"
  echo "    leaving the Google TTS settings screen in the foreground afterwards."
  exit 1
fi
echo "    tts engine          : $(adb "${DEVICE_ARG[@]}" shell settings get secure tts_default_synth | tr -d '\r')"
echo "==> Done."
