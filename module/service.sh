#!/system/bin/sh
MODDIR=${0%/*}
trace() { sh "$MODDIR/bin/diagnostics.sh" record "$@" >/dev/null 2>&1 || true; }
trace boot_wait
# SukiSU can dispatch this script before Android's package service is ready.
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done
entry=$(cmd package list packages -U com.anthropic.claude 2>/dev/null | grep '^package:com\.anthropic\.claude uid:' | head -n 1)
uid=${entry##*uid:}
case "$uid" in ''|*[!0-9]*) trace boot_uid_unavailable; exit 0 ;; esac
trace boot_supervisor uid="$uid"
firewall="$MODDIR/bin/firewall.sh"
exec /system/bin/sh "$firewall" supervise "$uid"
