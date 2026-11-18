#!/system/bin/sh
MODDIR=${MODPATH:-${0%/*}}
DATA_DIR=/data/adb/claude_privacy
mkdir -p "$DATA_DIR"
chmod 0700 "$DATA_DIR"
entry=$(cmd package list packages -U com.anthropic.claude 2>/dev/null | grep '^package:com\.anthropic\.claude uid:' | head -n 1)
uid=${entry##*uid:}
case "$uid" in ''|*[!0-9]*) ;; *) printf '%s\n' "$uid" > "$DATA_DIR/target-uid" ;; esac
chmod 0755 "$MODDIR"/*.sh "$MODDIR"/bin/*.sh
chmod 0644 "$MODDIR/default-config.json" "$MODDIR/hook.apk"
