#!/bin/bash
# One-shot demo readiness check — and where it's safe, the fix too.
#
#   ./demo_ready.sh          check everything, auto-fix the safe things
#   ./demo_ready.sh check    check only, change nothing
#
# Covers: both devices on adb, the RayNeo wake word (re-arms after every glasses
# reboot), all four apps installed, the MPQUIC client actually serving its HTTP/3
# socket (config restored + Connect + Start RX pressed if needed), and DevMon.

GLASS=A06B4A5D094C483
PHONE=R3CW80VT0ET
EC2="54.190.37.190:10000"
MODE="${1:-fix}"
FAIL=0

ok()   { printf "  \033[32mPASS\033[0m  %s\n" "$1"; }
fixd() { printf "  \033[33mFIXED\033[0m %s\n" "$1"; }
bad()  { printf "  \033[31mFAIL\033[0m  %s\n" "$1"; FAIL=1; }

scroll_top() {  # $1 device — dismiss keyboard, then drag content back to the top
  adb -s "$1" shell "input keyevent KEYCODE_BACK"
  for i in 1 2 3; do adb -s "$1" shell "input swipe 540 500 540 1600 250"; done
  sleep 1
}

hunt_tap() {  # $1 device  $2 resource-id — start at top, step down until found; 0/1
  scroll_top "$1"
  for i in 1 2 3 4 5 6 7; do
    [ "$(ui_tap "$1" "$2")" = "1" ] && { echo 1; return; }
    adb -s "$1" shell "input swipe 540 1450 540 450 300"
    sleep 1
  done
  echo 0
}

ui_tap() {  # $1 device  $2 resource-id  -> taps it if visible, prints 0/1
  adb -s "$1" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local xy
  xy=$(adb -s "$1" shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys,re
x=sys.stdin.read()
for m in re.finditer(r'<node[^>]*?/?>', x):
    t=m.group(0)
    rid=(re.search(r'resource-id=\"([^\"]*)\"',t) or [None,''])[1].split('/')[-1]
    if rid=='$2':
        b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',t)
        print((int(b.group(1))+int(b.group(3)))//2,(int(b.group(2))+int(b.group(4)))//2); break")
  [ -n "$xy" ] && { adb -s "$1" shell "input tap $xy"; echo 1; } || echo 0
}

ui_text() {  # $1 device  $2 resource-id -> prints the element's text
  adb -s "$1" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb -s "$1" shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys,re
x=sys.stdin.read()
for m in re.finditer(r'<node[^>]*?/?>', x):
    t=m.group(0)
    rid=(re.search(r'resource-id=\"([^\"]*)\"',t) or [None,''])[1].split('/')[-1]
    if rid=='$2':
        print((re.search(r'text=\"([^\"]*)\"',t) or [None,''])[1]); break"
}

echo "── devices ──────────────────────────────────────"
for pair in "$GLASS:glasses" "$PHONE:phone"; do
  d="${pair%%:*}"; n="${pair#*:}"
  if adb devices | grep -q "^$d[[:space:]]*device"; then ok "$n on adb"
  else bad "$n NOT on adb — plug it in and re-run"; fi
done

if adb devices | grep -q "^$GLASS[[:space:]]*device"; then
  echo "── glasses ──────────────────────────────────────"
  A=$(adb -s $GLASS shell settings get secure assistant | tr -d '\r')
  if [ "$A" = "null" ]; then ok "wake word disabled"
  elif [ "$MODE" = "check" ]; then bad "wake word ACTIVE — run glass_disable_wakeword.sh"
  else
    adb -s $GLASS shell settings delete secure voice_interaction_service >/dev/null
    adb -s $GLASS shell settings delete secure assistant >/dev/null
    fixd "wake word disabled (re-arms on every reboot)"
  fi
  adb -s $GLASS shell pm list packages | grep -q video.show.glass \
    && ok "glasses app installed" || bad "glasses app MISSING"
fi

if adb devices | grep -q "^$PHONE[[:space:]]*device"; then
  echo "── phone apps ───────────────────────────────────"
  for pair in "com.example.video.show.demo:ARFood" "com.mpquic.client:MPQUIC client" \
              "com.example.devmon:DevMon"; do
    p="${pair%%:*}"; n="${pair#*:}"
    adb -s $PHONE shell pm list packages | grep -q "^package:$p$" \
      && ok "$n installed" || bad "$n MISSING — see apk_backup_2026-08-07/"
  done

  echo "── mpquic client ────────────────────────────────"
  adb -s $PHONE shell "dumpsys deviceidle whitelist | grep -q com.mpquic.client" \
    && ok "background exemptions" \
    || { if [ "$MODE" = "check" ]; then bad "exemptions missing"; else
           adb -s $PHONE shell "dumpsys deviceidle whitelist +com.mpquic.client" >/dev/null
           adb -s $PHONE shell "cmd appops set com.mpquic.client RUN_ANY_IN_BACKGROUND allow"
           fixd "exemptions applied"; fi; }

  h3_up() { adb -s $PHONE shell "cat /proc/net/udp /proc/net/udp6 2>/dev/null" | awk '$2 ~ /B953$/' | grep -q .; }

  if h3_up; then
    ok "HTTP/3 intake listening on :47443"
  elif [ "$MODE" = "check" ]; then
    bad "h3 :47443 CLOSED — open MPQUIC client: Connect, then Start HTTP/3 RX"
  else
    # bring the whole thing up: launch, restore address if wiped, Connect, Start RX
    adb -s $PHONE shell "monkey -p com.mpquic.client -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
    sleep 3
    ADDR=$(ui_text $PHONE serverAddr)
    if [ "$ADDR" != "$EC2" ] && [ -n "$ADDR" ]; then
      adb -s $PHONE shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
      ui_tap $PHONE serverAddr >/dev/null; sleep 1
      adb -s $PHONE shell "input keyevent KEYCODE_MOVE_END"
      for i in $(seq 1 28); do adb -s $PHONE shell "input keyevent KEYCODE_DEL"; done
      adb -s $PHONE shell "input text '$EC2'"; sleep 1
      adb -s $PHONE shell "input keyevent KEYCODE_BACK"; sleep 1
      fixd "server address restored to $EC2 (leave the alt/IPv6 field empty)"
    fi
    L=/storage/emulated/0/Android/data/com.mpquic.client/files/mpquic/client.log
    already_connected() {
      adb -s $PHONE shell "cat $L 2>/dev/null" | tr -d '\r' \
        | awk '/starting client:/{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}' \
        | grep -q "connected (multipath"
    }
    if already_connected; then
      ok "tunnel already up — skipping Connect"
    elif [ "$(hunt_tap $PHONE connectBtn)" = "1" ]; then
      fixd "pressed Connect"; sleep 5
    else
      bad "could not find connectBtn — is a different screen open in the app?"
    fi
    if [ "$(hunt_tap $PHONE h3Btn)" = "1" ]; then
      fixd "pressed Start HTTP/3 RX"; sleep 3
    else
      bad "could not find h3Btn — scroll the app by hand and tap Start HTTP/3 RX"
    fi
    h3_up && ok "HTTP/3 intake now listening on :47443" \
          || bad "h3 :47443 still closed — open the app and check its log line"
  fi

  L=/storage/emulated/0/Android/data/com.mpquic.client/files/mpquic/client.log
  adb -s $PHONE shell "cat $L 2>/dev/null" | tr -d '\r' \
    | awk '/starting client:/{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}' \
    | grep -q "connected (multipath" \
    && ok "tunnel connected to $EC2" \
    || bad "tunnel NOT connected — press Connect in the app (alt/IPv6 field must be EMPTY)"

  echo "── devmon (local compute path, optional) ────────"
  if adb -s $PHONE shell "pidof com.example.devmon" | grep -q .; then
    ok "DevMon running (local path available)"
  else
    printf "  \033[33mNOTE\033[0m  DevMon not running — captures will fail over to the cloud path\n"
  fi
fi

echo "─────────────────────────────────────────────────"
[ $FAIL = 0 ] && echo "READY — run one throwaway capture to confirm end to end." \
             || echo "NOT READY — fix the FAIL lines above and re-run."
exit $FAIL
