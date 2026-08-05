#!/usr/bin/env bash
# One-time (per flash) hardware bring-up for the RayNeo X3 mic on the neo_idp_sg board.
# Pushes Qualcomm's ACDB audio calibration data so AudioRecord gets properly gained
# mic input instead of the low-volume/uncalibrated default.
#
# Source of the vendor files: design_docs/Glasses/glass/vendor (from the AI Recall SIT
# bring-up doc's push_ainaq_la.bat, minus the parts specific to that other project).
#
# Safe to re-run: disable-verity is a no-op if already disabled.

set -euo pipefail

VENDOR_SRC="/Users/sukoon/Documents/Hackathon26/design_docs/Glasses/glass"
DEVICE_ARG=()
[[ -n "${DEVICE_SERIAL:-}" ]] && DEVICE_ARG=(-s "$DEVICE_SERIAL")

adb "${DEVICE_ARG[@]}" wait-for-device

echo "==> Rooting and disabling dm-verity"
adb "${DEVICE_ARG[@]}" root
adb "${DEVICE_ARG[@]}" wait-for-device
adb "${DEVICE_ARG[@]}" disable-verity
adb "${DEVICE_ARG[@]}" shell sync
adb "${DEVICE_ARG[@]}" reboot
adb "${DEVICE_ARG[@]}" wait-for-device

echo "==> Remounting /vendor read-write"
adb "${DEVICE_ARG[@]}" root
adb "${DEVICE_ARG[@]}" wait-for-device
adb "${DEVICE_ARG[@]}" remount
sleep 5

echo "==> Pushing ACDB audio calibration files"
adb "${DEVICE_ARG[@]}" shell mkdir -p /vendor/etc/acdbdata/neo_idp_sg
adb "${DEVICE_ARG[@]}" push "$VENDOR_SRC/vendor" /

echo "==> Rebooting to apply the new audio calibration"
adb "${DEVICE_ARG[@]}" reboot
adb "${DEVICE_ARG[@]}" wait-for-device
adb "${DEVICE_ARG[@]}" root
adb "${DEVICE_ARG[@]}" wait-for-device
adb "${DEVICE_ARG[@]}" remount

echo "==> Done. Reconnect the mic test / VideoShowCase audio-enabled stream to verify."
