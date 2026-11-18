#!/system/bin/sh
uid=$(cat /data/adb/claude_privacy/target-uid 2>/dev/null || true)
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
pm uninstall dev.chx.claudeprivacy >/dev/null 2>&1 || true
