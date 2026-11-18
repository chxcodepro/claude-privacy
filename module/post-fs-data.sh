#!/system/bin/sh
MODDIR=${0%/*}
uid=$(cat /data/adb/claude_privacy/target-uid 2>/dev/null || true)
case "$uid" in ''|*[!0-9]*) exit 0 ;; esac
sh "$MODDIR/bin/firewall.sh" block "$uid" boot 2>/dev/null || true
