#!/system/bin/sh
DATA_DIR=/data/adb/claude_privacy
touch "${0%/*}/remove" || exit 1
mkdir -p "$DATA_DIR" || exit 1
exec 9>>"$DATA_DIR/firewall.lock"
flock -x 9 9>&9 || exit 1
rm -f "$DATA_DIR/watch-owner" "$DATA_DIR/process-lease"
uid=$(cat "$DATA_DIR/target-uid" 2>/dev/null || true)
case "$uid" in
  ''|*[!0-9]*) ;;
  *)
    for table in iptables ip6tables; do
      "$table" -w -D OUTPUT -m owner --uid-owner "$uid" -j CP_CLAUDE 2>/dev/null || true
      "$table" -w -F CP_CLAUDE 2>/dev/null || true
      "$table" -w -X CP_CLAUDE 2>/dev/null || true
    done
    ;;
esac
flock -u 9 9>&9
pm uninstall dev.chx.claudeprivacy >/dev/null 2>&1 || true
