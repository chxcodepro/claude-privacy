#!/system/bin/sh
set -eu

DATA_DIR=/data/adb/claude_privacy
CHAIN=CP_CLAUDE
STATUS="$DATA_DIR/status.json"
UID_FILE="$DATA_DIR/target-uid"
OBSERVATION="$DATA_DIR/observed-exit"
mkdir -p "$DATA_DIR"
chmod 0700 "$DATA_DIR"
action=${1:-}
uid=${2:-}
case "$action" in block|probe|observed|allow) ;; *) echo 'Unknown firewall action' >&2; exit 2 ;; esac

case "$uid" in ''|*[!0-9]*) echo 'Invalid target UID' >&2; exit 2 ;; esac
[ "$uid" -ge 10000 ] || { echo 'Target UID too low' >&2; exit 2; }
command -v iptables >/dev/null 2>&1 || { echo 'iptables unavailable' >&2; exit 1; }
command -v ip6tables >/dev/null 2>&1 || { echo 'ip6tables unavailable' >&2; exit 1; }
command -v iptables-restore >/dev/null 2>&1 || { echo 'iptables-restore unavailable' >&2; exit 1; }

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
  {
    printf '*filter\n-F %s\n' "$CHAIN"
    case "$1" in
      probe)
        shift
        for address in "$@"; do printf -- '-A %s -d %s -p tcp --dport 443 -j RETURN\n' "$CHAIN" "$address"; done
        ;;
      allow) printf -- '-A %s -o %s -j RETURN\n' "$CHAIN" "$2" ;;
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

case "$action" in
  block)
    replace_ipv4 block
    ip6tables -w -R "$CHAIN" 1 -j REJECT
    reason=${3:-blocked}
    case "$reason" in boot|startup|probe_failed|no_allowed_ips|ip_denied|mainland_ip|ip_region_unknown|network_changed|background|unsupported_version|hook_failed|blocked) ;; *) reason=blocked ;; esac
    case "$reason" in boot|startup|network_changed) rm -f "$OBSERVATION" ;; esac
    read_observation
    {
      printf '{"state":"blocked","reason":"%s"' "$reason"
      observation_fields
      printf '}\n'
    } | write_status
    ;;
  probe)
    shift 2
    [ "$#" -ge 1 ] && [ "$#" -le 32 ] || { echo 'Invalid probe address count' >&2; exit 2; }
    for ip in "$@"; do
      case "$ip" in ''|*[!0-9.]*) echo 'Invalid probe address' >&2; exit 2 ;; esac
    done
    # Keep every non-probe destination blocked while Application.attach waits.
    replace_ipv4 probe "$@"
    ip6tables -w -R "$CHAIN" 1 -j REJECT
    printf '{"state":"blocked","reason":"checking"}\n' | write_status
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
    case "$applied_timezone" in *[!A-Za-z0-9_+./-]*) echo 'Invalid timezone readback' >&2; exit 2 ;; esac
    case "$applied_language" in *[!A-Za-z0-9-]*) echo 'Invalid language readback' >&2; exit 2 ;; esac
    replace_ipv4 allow "$iface"
    ip6tables -w -R "$CHAIN" 1 -j REJECT
    save_observation
    { printf '{"state":"allowed"'; observation_fields; printf '}\n'; } | write_status
    ;;
  *) echo 'Usage: firewall.sh block|probe|observed|allow UID [args]' >&2; exit 2 ;;
esac
