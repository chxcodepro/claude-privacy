#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/bin/targets.sh"
trace() { sh "$MODDIR/bin/diagnostics.sh" record "$@" >/dev/null 2>&1 || true; }
trace boot_wait
# SukiSU can dispatch this script before Android's package service is ready.
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done
firewall="$MODDIR/bin/firewall.sh"
user=$(am get-current-user)
case "$user" in ''|*[!0-9]*) trace boot_uid_unavailable; exit 0 ;; esac
for package in $TARGET_PACKAGES; do
  uid=$(target_uid "$package" "$user")
  [ -n "$uid" ] || continue
  trace boot_supervisor uid="$uid"
  CP_TARGET="$package" /system/bin/sh "$firewall" supervise "$uid" &
done
wait
