#!/usr/bin/env bash
# Repeatable dev-convenience setup for VideoShowCase-based glass + phone apps.
# Run this after every fresh install to skip clicking through permission dialogs
# and to apply a few QoL device settings for demoing.
#
# Usage: ./glass_dev_setup.sh [glass|phone|both]   (default: both)

set -euo pipefail

TARGET="${1:-both}"
GLASS_PKG="com.example.video.show.glass"
PHONE_PKG="com.example.video.show.demo"
DEVICE_ARG=()
[[ -n "${DEVICE_SERIAL:-}" ]] && DEVICE_ARG=(-s "$DEVICE_SERIAL")

grant_glass_perms() {
  echo "==> Granting permissions to $GLASS_PKG"
  for perm in CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    adb "${DEVICE_ARG[@]}" shell pm grant "$GLASS_PKG" "android.permission.$perm" || true
  done
  # NEARBY_WIFI_DEVICES only exists on Android 13+ (API 33+); ignore failure on older builds.
  adb "${DEVICE_ARG[@]}" shell pm grant "$GLASS_PKG" android.permission.NEARBY_WIFI_DEVICES || true
}

grant_phone_perms() {
  echo "==> Granting permissions to $PHONE_PKG"
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    adb "${DEVICE_ARG[@]}" shell pm grant "$PHONE_PKG" "android.permission.$perm" || true
  done
  adb "${DEVICE_ARG[@]}" shell pm grant "$PHONE_PKG" android.permission.NEARBY_WIFI_DEVICES || true
}

case "$TARGET" in
  glass) grant_glass_perms ;;
  phone) grant_phone_perms ;;
  both)  grant_glass_perms; grant_phone_perms ;;
  *) echo "Usage: $0 [glass|phone|both]"; exit 1 ;;
esac

echo "==> Applying demo QoL device settings"
adb "${DEVICE_ARG[@]}" shell settings put system accelerometer_rotation 0
adb "${DEVICE_ARG[@]}" shell cmd media_session volume --set 15
adb "${DEVICE_ARG[@]}" shell svc wifi enable

echo "==> Done."
