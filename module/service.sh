#!/system/bin/sh
MODDIR=${0%/*}
entry=$(cmd package list packages -U com.anthropic.claude 2>/dev/null | grep '^package:com\.anthropic\.claude uid:' | head -n 1)
uid=${entry##*uid:}
case "$uid" in ''|*[!0-9]*) exit 0 ;; esac
sh "$MODDIR/bin/firewall.sh" block "$uid" boot
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done
user=$((uid / 100000))
content call --user "$user" --uri content://dev.chx.claudeprivacy.bridge --method prepare >/dev/null 2>&1 || true
