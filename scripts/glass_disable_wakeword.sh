#!/usr/bin/env bash
# Stops RayNeo's always-on voice assistant from popping up mid-workflow when it
# mishears its wake word.
#
# The hotword detector is hosted by com.rayneo.aispeech/.wakeup.RayNeoVoiceInteractionService,
# a privileged system app, bound via the `assistant` and `voice_interaction_service` secure
# settings. Deleting both unbinds it. Note `settings put ... ""` is rejected — the setting has
# to be deleted, not blanked.
#
# Safe for the capture app: it binds Google's recognizer by explicit ComponentName and never
# goes through the system assistant, so speech and TTS are unaffected. Verified on-device.
#
# DOES NOT SURVIVE A REBOOT. Confirmed on 2026-08-06: after a power cycle both settings were
# back to RayNeo's service and the wake word was live again. Re-run this after every reboot.
#
# Undo with glass_restore_assistant.sh.
#
# Usage: ./glass_disable_wakeword.sh

set -euo pipefail

DEVICE_ARG=()
[[ -n "${DEVICE_SERIAL:-}" ]] && DEVICE_ARG=(-s "$DEVICE_SERIAL")

adb "${DEVICE_ARG[@]}" wait-for-device

echo "==> Unbinding the RayNeo voice interaction service"
adb "${DEVICE_ARG[@]}" shell settings delete secure voice_interaction_service
adb "${DEVICE_ARG[@]}" shell settings delete secure assistant

echo "==> Verifying"
a=$(adb "${DEVICE_ARG[@]}" shell settings get secure assistant | tr -d '\r')
v=$(adb "${DEVICE_ARG[@]}" shell settings get secure voice_interaction_service | tr -d '\r')
if [[ "$a" == "null" && "$v" == "null" ]]; then
  echo "    assistant / voice_interaction_service : unset (wake word disabled)"
else
  echo "    NOT unset — assistant='$a' voice_interaction_service='$v'"
  echo "    Something re-applied them. Re-run, and if it keeps reverting fall back to:"
  echo "      adb shell pm disable-user --user 0 com.rayneo.aispeech"
  exit 1
fi

echo "==> Confirming the capture app's speech + TTS still work"
echo "    (binds Google by ComponentName, so it does not depend on the assistant)"
echo "==> Done. NOTE: this does not survive a reboot — re-run after every power cycle."
