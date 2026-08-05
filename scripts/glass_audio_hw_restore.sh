#!/usr/bin/env bash
# Restores the on-device vendor audio files from a backup taken before
# glass_audio_hw_setup.sh overwrote them. Use if the new calibration
# turns out worse than stock.
#
# Usage: ./glass_audio_hw_restore.sh <path-to-backup-dir>

set -euo pipefail

BACKUP_DIR="${1:?Usage: $0 <path-to-backup-dir>}"
DEVICE_ARG=()
[[ -n "${DEVICE_SERIAL:-}" ]] && DEVICE_ARG=(-s "$DEVICE_SERIAL")

adb "${DEVICE_ARG[@]}" wait-for-device
adb "${DEVICE_ARG[@]}" root
adb "${DEVICE_ARG[@]}" wait-for-device
adb "${DEVICE_ARG[@]}" remount

echo "==> Restoring vendor audio files from $BACKUP_DIR"
adb "${DEVICE_ARG[@]}" push "$BACKUP_DIR/mixer_paths_neo_idp_sg.xml" /vendor/etc/mixer_paths_neo_idp_sg.xml
adb "${DEVICE_ARG[@]}" push "$BACKUP_DIR/resourcemanager_neo_idp_sg.xml" /vendor/etc/resourcemanager_neo_idp_sg.xml
adb "${DEVICE_ARG[@]}" push "$BACKUP_DIR/init.qcom.post_boot.sh" /vendor/bin/init.qcom.post_boot.sh
adb "${DEVICE_ARG[@]}" push "$BACKUP_DIR/acdbdata/IDP_neo_sg_acdb_cal.acdb" /vendor/etc/acdbdata/neo_idp_sg/IDP_neo_sg_acdb_cal.acdb
adb "${DEVICE_ARG[@]}" push "$BACKUP_DIR/acdbdata/IDP_neo_sg_workspaceFileXml.qwsp" /vendor/etc/acdbdata/neo_idp_sg/IDP_neo_sg_workspaceFileXml.qwsp

echo "==> Rebooting to apply restored calibration"
adb "${DEVICE_ARG[@]}" reboot
echo "==> Done."
