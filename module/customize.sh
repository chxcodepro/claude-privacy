#!/system/bin/sh
MODDIR=${MODPATH:-${0%/*}}
. "$MODDIR/bin/targets.sh"
user=$(am get-current-user)
for package in $TARGET_PACKAGES; do
  target_context "$package"
  mkdir -p "$DATA_DIR"
  chmod 0700 "$DATA_DIR"
  uid=$(target_uid "$package" "$user")
  case "$uid" in ''|*[!0-9]*) ;; *) printf '%s\n' "$uid" > "$DATA_DIR/target-uid" ;; esac
done
chmod 0755 "$MODDIR"/*.sh "$MODDIR"/bin/*.sh
chmod 0644 "$MODDIR/default-config.json" "$MODDIR/hook.apk"
