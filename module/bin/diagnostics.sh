#!/system/bin/sh
set -efu

MODDIR=${0%/*/*}
. "$MODDIR/bin/targets.sh"
DATA_DIR=$DATA_ROOT
umask 077
mkdir -p "$DATA_DIR"
chmod 0700 "$DATA_DIR"

case "${1:-}" in
  record)
    shift
    [ "$#" -ge 1 ] && [ "$#" -le 8 ] || exit 2
    for token in "$@"; do
      # Only event codes and numeric IDs enter the log; reject messages, URLs and IPs.
      case "$token" in ''|*[!A-Za-z0-9_=-]*) exit 2 ;; esac
      [ "${#token}" -le 64 ] || exit 2
    done
    [ "${#*}" -le 160 ] || exit 2
    exec 6>>"$DATA_DIR/diagnostics.lock"
    /system/bin/flock -xn 6 6>&6 || exit 0
    log="$DATA_DIR/diagnostics.log"
    if [ -f "$log" ] && [ "$(wc -c < "$log")" -ge 65536 ]; then
      mv -f "$log" "$log.1"
    fi
    printf '%s pid=%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$$" "$*" >> "$log"
    ;;
  export)
    printf 'Claude Privacy diagnostics v1\nUTC: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '\n[module]\n'
    sed -n '/^version=/p; /^versionCode=/p' "$MODDIR/module.prop"
    printf 'android_sdk=%s\n' "$(getprop ro.build.version.sdk)"
    printf 'hook_version_code='
    /system/bin/timeout 2 cmd package list packages --show-versioncode dev.chx.claudeprivacy 2>/dev/null |
      sed -n 's/^package:dev\.chx\.claudeprivacy versionCode:\([0-9]*\)$/\1/p' || true
    printf 'claude_version_code='
    /system/bin/timeout 2 cmd package list packages --show-versioncode com.anthropic.claude 2>/dev/null |
      sed -n 's/^package:com\.anthropic\.claude versionCode:\([0-9]*\)$/\1/p' || true
    printf 'duet_version_code='
    /system/bin/timeout 2 cmd package list packages --show-versioncode com.anthropic.aibo 2>/dev/null |
      sed -n 's/^package:com\.anthropic\.aibo versionCode:\([0-9]*\)$/\1/p' || true
    for package in $TARGET_PACKAGES; do
      target_context "$package"
      printf '\n[guard %s]\n' "$package"
      /system/bin/timeout 3 sh "$MODDIR/bin/firewall.sh" watch-status 2>/dev/null || echo status_query_failed
    for name in supervisor watch firewall; do
      (
        exec 5>>"$DATA_DIR/$name.lock"
        if /system/bin/flock -sn 5 5>&5 2>/dev/null; then
          printf '%s_lock=free\n' "$name"
        else
          printf '%s_lock=busy_or_unreadable\n' "$name"
        fi
      )
    done
    owner= start=
    if [ -f "$DATA_DIR/watch-owner" ]; then
      IFS=' ' read -r owner start < "$DATA_DIR/watch-owner" || true
    fi
    case "$owner:$start" in
      *[!0-9:]*|:*|*:) echo owner_marker=missing_or_invalid ;;
      *)
        printf 'owner_pid=%s owner_start=%s\n' "$owner" "$start"
        if [ -r "/proc/$owner/status" ]; then
          sed -n '/^State:/p; /^PPid:/p; /^Uid:/p' "/proc/$owner/status" 2>/dev/null || echo owner_proc=exited_during_snapshot
          # Cgroup IDs help identify task-cleaner membership without dumping cmdline or environment.
          grep -E '^[0-9]+:[A-Za-z0-9_,]*:/[A-Za-z0-9_/:=-]*$' "/proc/$owner/cgroup" 2>/dev/null || true
        else
          echo owner_proc=unavailable
        fi
        ;;
    esac
    if [ -f "$DATA_DIR/process-lease" ]; then echo lease=present; else echo lease=absent; fi
    printf '\n[last gate]\n'
    if [ -f "$DATA_DIR/status.json" ]; then
      # Export selected enum/numeric fields only. In particular, omit detail and all identity values.
      for key in state reason; do
        sed -n "s/.*\"$key\":\"\([a-z_]*\)\".*/$key=\1/p" "$DATA_DIR/status.json"
      done
      for key in updatedAt revision; do
        sed -n "s/.*\"$key\":\([0-9]*\).*/$key=\1/p" "$DATA_DIR/status.json"
      done
    fi
    done
    DATA_DIR=$DATA_ROOT
    printf '\n[root events]\n'
    for file in "$DATA_DIR/diagnostics.log.1" "$DATA_DIR/diagnostics.log"; do
      if [ -f "$file" ]; then tail -n 150 "$file" 2>/dev/null || true; fi
    done
    printf '\n[hook events]\n'
    # A dedicated tag and a strict format keep other apps' data and arbitrary exception text out.
    /system/bin/timeout 2 logcat -d -v raw -s 'ClaudePrivacyDiag:I' '*:S' 2>/dev/null |
      grep -E '^cpdiag [A-Za-z0-9_= -]{1,240}$' | tail -n 100 || true
    ;;
  *) echo 'Usage: diagnostics.sh record EVENT [key=value ...] | export' >&2; exit 2 ;;
esac
