#!/system/bin/sh
MODDIR=${0%/*}
entry=$(cmd package list packages -U com.anthropic.claude 2>/dev/null | grep '^package:com\.anthropic\.claude uid:' | head -n 1)
uid=${entry##*uid:}
case "$uid" in ''|*[!0-9]*) exit 0 ;; esac
firewall="$MODDIR/bin/firewall.sh"
sh "$firewall" block "$uid" boot || exit 1
sh "$firewall" watch "$uid" &
watcher=$!
trap 'kill "$watcher" 2>/dev/null || true; exit 0' HUP INT TERM
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done
user=$((uid / 100000))
# BridgeProvider is credential-encrypted; boot_completed may precede the first unlock.
while [ "$(am get-started-user-state "$user" 2>/dev/null)" != RUNNING_UNLOCKED ]; do sleep 2; done
bridge_ready=false
for attempt in 1 2 3 4 5; do
  out=$(content call --user "$user" --uri content://dev.chx.claudeprivacy.bridge --method prepare 2>&1)
  if [ "$out" = 'Result: Bundle[{result=ready}]' ]; then
    bridge_ready=true
    break
  fi
  sleep 2
done
[ "$bridge_ready" = true ] || log -t ClaudePrivacyGate 'Hook bridge not ready after user unlock'
wait "$watcher"
