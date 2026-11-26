#!/system/bin/sh
set -efu

MODDIR=${0%/*/*}
. "$MODDIR/bin/targets.sh"
target_context "${CP_TARGET:-com.anthropic.claude}"
STATUS="$DATA_DIR/status.json"
UID_FILE="$DATA_DIR/target-uid"
OBSERVATION="$DATA_DIR/observed-exit"
LEASE="$DATA_DIR/process-lease"
WATCHER="$DATA_DIR/watch-owner"
trace() { sh "$MODDIR/bin/diagnostics.sh" record "$@" uid="${uid:-0}" >/dev/null 2>&1 || true; }
mkdir -p "$DATA_DIR"
chmod 0700 "$DATA_DIR"
action=${1:-}
uid=${2:-}
case "$action" in block|probe|observed|allow|watch|supervise|ensure-watch|watch-status) ;; *) echo 'Unknown firewall action' >&2; exit 2 ;; esac

exec 8>>"$DATA_DIR/watch.lock"
watch_lock() { /system/bin/flock "$1" 8 8>&8; }
watcher_running() {
  [ -s "$WATCHER" ] || return 1
  if lock_error=$(watch_lock -sn 2>&1); then
    watch_lock -u
    return 1
  fi
  [ -z "$lock_error" ]
}
if [ "$action" = watch-status ]; then
  if watcher_running; then echo running; else echo stopped; fi
  exit 0
fi

case "$uid" in ''|*[!0-9]*) echo 'Invalid target UID' >&2; exit 2 ;; esac
[ "$uid" -ge 10000 ] || { echo 'Target UID too low' >&2; exit 2; }
command -v iptables >/dev/null 2>&1 || { echo 'iptables unavailable' >&2; exit 1; }
command -v ip6tables >/dev/null 2>&1 || { echo 'ip6tables unavailable' >&2; exit 1; }
command -v iptables-restore >/dev/null 2>&1 || { echo 'iptables-restore unavailable' >&2; exit 1; }
command -v flock >/dev/null 2>&1 || { echo 'flock unavailable' >&2; exit 1; }
exec 9>>"$DATA_DIR/firewall.lock"
# Android mksh only passes the descriptor to flock when it is explicitly redirected.
firewall_lock() { flock "$1" 9 9>&9; }
trace firewall_wait action="$action"
firewall_lock -x
trace firewall_acquired action="$action"
[ ! -e "$MODDIR/remove" ] || { echo '模块正在移除。' >&2; exit 1; }

ensure_chain() {
  table=$1
  "$table" -w -N "$CHAIN" 2>/dev/null || true
  if ! "$table" -w -S "$CHAIN" | grep -q '^-A '; then
    "$table" -w -A "$CHAIN" -j REJECT
  fi
  if ! "$table" -w -C OUTPUT -m owner --uid-owner "$uid" -j "$CHAIN" 2>/dev/null; then
    "$table" -w -I OUTPUT 1 -m owner --uid-owner "$uid" -j "$CHAIN"
  fi
}

previous=$(cat "$UID_FILE" 2>/dev/null || true)
if [ -n "$previous" ] && [ "$previous" != "$uid" ]; then
  iptables -w -D OUTPUT -m owner --uid-owner "$previous" -j "$CHAIN" 2>/dev/null || true
  ip6tables -w -D OUTPUT -m owner --uid-owner "$previous" -j "$CHAIN" 2>/dev/null || true
fi
ensure_chain iptables
ensure_chain ip6tables
printf '%s\n' "$uid" > "$UID_FILE"

# Commit complete chain replacements together; a flushed live chain would leak.
replace_ipv4() {
  if [ "$1" != block ]; then
    # The kernel expires the exception even if the root daemon is killed.
    now=$(/system/bin/date +%s) || return 1
    until_utc=$(/system/bin/date -u -d "@$((now + 8))" +%Y-%m-%dT%H:%M:%S) || return 1
  fi
  {
    printf '*filter\n-F %s\n' "$CHAIN"
    case "$1" in
      probe)
        shift
        for address in "$@"; do
          printf -- '-A %s -d %s -p tcp --dport 443 -m time --datestop %s -j RETURN\n' "$CHAIN" "$address" "$until_utc"
        done
        ;;
      allow) printf -- '-A %s -o %s -m time --datestop %s -j RETURN\n' "$CHAIN" "$2" "$until_utc" ;;
    esac
    printf -- '-A %s -j REJECT\nCOMMIT\n' "$CHAIN"
  } | iptables-restore -w 5 --noflush
}

write_status() {
  temp="$DATA_DIR/status.json.$$"
  revision=$(cat "$DATA_DIR/status-revision" 2>/dev/null || true)
  case "$revision" in ''|*[!0-9]*) revision=0 ;; esac
  revision=$((revision + 1))
  printf '%s\n' "$revision" > "$DATA_DIR/status-revision"
  sed "s/}$/,\"updatedAt\":$(date +%s),\"revision\":$revision}/" > "$temp"
  mv -f "$temp" "$STATUS"
}

read_observation() {
  observed= country= checked_at= zone= applied_timezone= applied_language=
  if [ -f "$OBSERVATION" ]; then
    IFS=' ' read -r observed country checked_at zone applied_timezone applied_language < "$OBSERVATION"
  fi
}

save_observation() {
  printf '%s %s %s %s %s %s\n' "$observed" "$country" "$checked_at" "$zone" "$applied_timezone" "$applied_language" > "$OBSERVATION.$$"
  mv -f "$OBSERVATION.$$" "$OBSERVATION"
}

observation_fields() {
  [ -n "$observed" ] || return 0
  printf ',"ip":"%s","countryCode":"%s","checkedAt":%s' "$observed" "$country" "$checked_at"
  [ "$zone" = "-" ] || printf ',"timezone":"%s"' "$zone"
  case "$applied_timezone" in ""|-) ;; *) printf ',"appliedTimezone":"%s"' "$applied_timezone" ;; esac
  case "$applied_language" in ""|-) ;; *) printf ',"appliedLanguage":"%s"' "$applied_language" ;; esac
}

process_start() {
  case "$1" in ''|0|*[!0-9]*) return 1 ;; esac
  IFS= read -r stat_line 2>/dev/null < "/proc/$1/stat" || return 1
  # The name field may contain whitespace or parentheses. After it, state is field 3.
  set -- ${stat_line##*) }
  case "${1:-}" in ''|Z|X|x) return 1 ;; esac
  started_at=${20:-}
  case "$started_at" in ''|*[!0-9]*) return 1 ;; esac
}

lease_alive() {
  [ -f "$LEASE" ] || return 1
  IFS=' ' read -r lease_pid lease_start lease_mode lease_args 2>/dev/null < "$LEASE" || return 1
  case "$lease_mode" in probe|allow) ;; *) return 1 ;; esac
  [ -n "$lease_args" ] && process_start "$lease_pid" && [ "$lease_start" = "$started_at" ]
}

save_lease() {
  printf '%s %s %s %s\n' "$lease_pid" "$lease_start" "$1" "$2" > "$LEASE.$$"
  mv -f "$LEASE.$$" "$LEASE"
}

close_access() {
  replace_ipv4 block || return 1
  ip6tables -w -R "$CHAIN" 1 -j REJECT || return 1
  rm -f "$LEASE" || return 1
  reason=$1
  case "$reason" in boot|startup|network_changed) rm -f "$OBSERVATION" ;; esac
  read_observation
  {
    printf '{"state":"blocked","reason":"%s"' "$reason"
    observation_fields
    printf '}\n'
  } | write_status
  trace access_closed reason="$reason"
}

require_watcher() {
  watcher_running || { trace watch_not_ready action="$action"; echo 'WATCHDOG_UNAVAILABLE' >&2; exit 1; }
}

await_watcher() {
  if watcher_running; then return 0; fi
  trace watch_wait_started action="$action"
  firewall_lock -u
  n=0
  while ! watcher_running && [ "$n" -lt 25 ]; do
    sleep 0.1
    n=$((n + 1))
  done
  firewall_lock -x
  require_watcher
  trace watch_wait_ready action="$action"
}

start_supervisor() {
  if watcher_running; then return 0; fi
  trace supervisor_requested
  # Only the module's root service or settings can launch the independent supervisor.
  /system/bin/setsid /system/bin/sh "$0" supervise "$uid" </dev/null >"$DATA_DIR/watch.log" 2>&1 8>&- 9>&- &
  await_watcher
}

watch_owns_marker() {
  [ -f "$WATCHER" ] &&
    IFS=' ' read -r owner_pid owner_start 2>/dev/null < "$WATCHER" &&
    [ "$owner_pid" = "$$" ] && [ "$owner_start" = "$watch_started_at" ]
}

stop_watching() {
  trace watch_stopping owner="$$"
  firewall_lock -x || return
  if watch_owns_marker; then
    if [ -f "$LEASE" ]; then close_access process_exited || log -t ClaudePrivacyGate 'Failed to revoke network lease'; fi
    rm -f "$WATCHER"
  fi
}

case "$action" in
  block)
    reason=${3:-blocked}
    case "$reason" in boot|startup|probe_failed|no_allowed_ips|ip_denied|mainland_ip|ip_region_unknown|watchdog_unavailable|network_changed|background|unsupported_version|hook_failed|blocked) ;; *) reason=blocked ;; esac
    close_access "$reason"
    ;;
  probe)
    lease_pid=${3:-}
    await_watcher
    process_start "$lease_pid" || { echo '无法确认 Claude 审核进程。' >&2; exit 1; }
    lease_start=$started_at
    shift 3
    [ "$#" -ge 1 ] && [ "$#" -le 32 ] || { echo 'Invalid probe address count' >&2; exit 2; }
    for ip in "$@"; do
      case "$ip" in ''|*[!0-9.]*) echo 'Invalid probe address' >&2; exit 2 ;; esac
    done
    # Write the lease before opening any destinations, even for a probe.
    save_lease probe "$*"
    replace_ipv4 probe "$@"
    ip6tables -w -R "$CHAIN" 1 -j REJECT
    printf '{"state":"blocked","reason":"checking"}\n' | write_status
    trace probe_opened owner="$lease_pid"
    ;;
  observed)
    observed=${3:-}
    country=${4:-}
    zone=${5:-}
    case "$observed" in ''|*[!0-9.]*) echo 'Invalid public IPv4' >&2; exit 2 ;; esac
    case "$country" in [A-Z][A-Z]) ;; *) echo 'Invalid country' >&2; exit 2 ;; esac
    case "$zone" in ''|*[!A-Za-z0-9_+./-]*) echo 'Invalid timezone' >&2; exit 2 ;; esac
    checked_at=$(date +%s)
    applied_timezone=- applied_language=-
    save_observation
    { printf '{"state":"blocked","reason":"checking"'; observation_fields; printf '}\n'; } | write_status
    trace exit_observed
    ;;
  allow)
    ip=${3:-}
    iface=${4:-}
    case "$ip" in ''|*[!0-9.]*) echo 'Invalid public IPv4' >&2; exit 2 ;; esac
    case "$iface" in ''|*[!A-Za-z0-9_.-]*) echo 'Invalid interface' >&2; exit 2 ;; esac
    # Old Hook APKs do not supply a region. Keep their startup blocked after a ZIP upgrade.
    case "${5:-}" in
      CN) echo 'Mainland China IPv4 is not allowed' >&2; exit 2 ;;
      [A-Z][A-Z]) ;;
      *) echo 'Verified IP country required; update the Claude Privacy Hook' >&2; exit 2 ;;
    esac
    read_observation
    [ "$observed" = "$ip" ] && [ "$country" = "$5" ] || { echo 'Verified Claude edge exit required' >&2; exit 2; }
    applied_timezone=${6:--}
    applied_language=${7:--}
    require_watcher
    lease_alive && [ "$lease_pid" = "${8:-}" ] || { echo 'Claude 公网 IPv4 审核进程已退出。' >&2; exit 1; }
    case "$applied_timezone" in *[!A-Za-z0-9_+./-]*) echo 'Invalid timezone readback' >&2; exit 2 ;; esac
    case "$applied_language" in *[!A-Za-z0-9-]*) echo 'Invalid language readback' >&2; exit 2 ;; esac
    save_lease allow "$iface"
    replace_ipv4 allow "$iface"
    ip6tables -w -R "$CHAIN" 1 -j REJECT
    save_observation
    { printf '{"state":"allowed"'; observation_fields; printf '}\n'; } | write_status
    trace access_allowed owner="$lease_pid"
    ;;
  ensure-watch)
    start_supervisor
    ;;
  supervise)
    exec 7>>"$DATA_DIR/supervisor.lock"
    /system/bin/flock -xn 7 7>&7 || exit 0
    trace supervisor_started owner="$$"
    firewall_lock -u
    while [ -f "$MODDIR/module.prop" ] && [ ! -e "$MODDIR/remove" ]; do
      if ! watcher_running; then
        if /system/bin/sh "$0" watch "$uid" 7>&- 8>&- 9>&-; then
          trace watch_child_exited status=0
        else
          trace watch_child_exited status="$?"
          log -t ClaudePrivacyGate 'Exit watchdog stopped; retrying'
        fi
      fi
      sleep 0.5
    done
    ;;
  watch)
    if watcher_running; then exit 0; fi
    watch_lock -xn || exit 1
    # Do not renew an old grant after restarting the watcher.
    if [ -f "$LEASE" ]; then close_access watchdog_unavailable; fi
    process_start "$$"
    watch_started_at=$started_at
    printf '%s %s\n' "$$" "$watch_started_at" > "$WATCHER.$$"
    mv -f "$WATCHER.$$" "$WATCHER"
    trace watch_ready owner="$$"
    trap stop_watching EXIT
    trap 'exit 0' HUP INT TERM
    firewall_lock -u
    ticks=0
    beats=0
    bridge_app=
    while [ -f "$MODDIR/module.prop" ] && [ ! -e "$MODDIR/remove" ] && watch_owns_marker; do
      if [ -f "$LEASE" ] && { ! lease_alive || [ "$ticks" -ge 4 ]; }; then
        firewall_lock -x
        # Re-read under the lock before revoking or renewing a possibly replaced lease.
        if watch_owns_marker && [ -f "$LEASE" ]; then
          if ! lease_alive; then
            close_access process_exited || log -t ClaudePrivacyGate 'Failed to revoke network lease'
          elif ! replace_ipv4 "$lease_mode" $lease_args; then
            trace lease_renew_failed
            close_access watchdog_unavailable || log -t ClaudePrivacyGate 'Failed to renew network lease'
          fi
        fi
        firewall_lock -u
        ticks=0
      fi
      # Package visibility can disappear even after a successful persistable grant on ColorOS.
      # Prepare again after a process transition, outside the firewall lock.
      current_app=$(pidof "$TARGET_PACKAGE" 2>/dev/null || true)
      if [ "$current_app" != "$bridge_app" ]; then
        reply=$(/system/bin/timeout 2 /system/bin/content call --user "$((uid / 100000))" \
          --uri content://dev.chx.claudeprivacy.bridge --method prepare 2>/dev/null) || reply=
        if [ "$reply" = 'Result: Bundle[{result=ready}]' ]; then
          bridge_app=$current_app
          trace bridge_prepared
        else
          trace bridge_prepare_failed
        fi
      fi
      ticks=$(( (ticks + 1) % 5 ))
      beats=$(( (beats + 1) % 20 ))
      if [ "$beats" -eq 0 ]; then trace watch_heartbeat owner="$$"; fi
      sleep 0.5
    done
    ;;
esac
