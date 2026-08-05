#!/usr/bin/env bash
# Restores RayNeo's voice assistant / wake-word, undoing glass_disable_wakeword.sh.
set -euo pipefail
DEVICE_ARG=()
[[ -n "${DEVICE_SERIAL:-}" ]] && DEVICE_ARG=(-s "$DEVICE_SERIAL")
adb "${DEVICE_ARG[@]}" shell settings put secure assistant "com.rayneo.aispeech/.wakeup.RayNeoVoiceInteractionService"
adb "${DEVICE_ARG[@]}" shell settings put secure voice_interaction_service "com.rayneo.aispeech/.wakeup.RayNeoVoiceInteractionService"
echo "assistant                 : $(adb "${DEVICE_ARG[@]}" shell settings get secure assistant)"
echo "voice_interaction_service : $(adb "${DEVICE_ARG[@]}" shell settings get secure voice_interaction_service)"
