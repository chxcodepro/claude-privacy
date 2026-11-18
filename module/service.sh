#!/system/bin/sh
MODDIR=${0%/*}
entry=$(cmd package list packages -U com.anthropic.claude 2>/dev/null | grep '^package:com\.anthropic\.claude uid:' | head -n 1)
uid=${entry##*uid:}
case "$uid" in ''|*[!0-9]*) exit 0 ;; esac
sh "$MODDIR/bin/firewall.sh" block "$uid" boot
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done
user=$((uid / 100000))
# BridgeProvider is credential-encrypted; boot_completed may precede the first unlock.
while [ "$(am get-started-user-state "$user" 2>/dev/null)" != RUNNING_UNLOCKED ]; do sleep 2; done
for attempt in 1 2 3 4 5; do
  out=$(content call --user "$user" --uri content://dev.chx.claudeprivacy.bridge --method prepare 2>&1)
  [ "$out" != 'Result: Bundle[{result=ready}]' ] || exit 0
  sleep 2
done
log -t ClaudePrivacyGate 'Hook bridge not ready after user unlock'
