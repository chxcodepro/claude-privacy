#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/bin/targets.sh"
for package in $TARGET_PACKAGES; do
  target_context "$package"
  uid=$(cat "$DATA_DIR/target-uid" 2>/dev/null || true)
  case "$uid" in ''|*[!0-9]*) continue ;; esac
  sh "$MODDIR/bin/firewall.sh" block "$uid" boot 2>/dev/null || true
done
