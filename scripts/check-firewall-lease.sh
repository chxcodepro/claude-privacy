#!/bin/sh
set -eu

work=$(mktemp -d)
watcher= app=
cleanup() {
  if [ -n "$app" ]; then kill "$app" 2>/dev/null || true; wait "$app" 2>/dev/null || true; fi
  if [ -n "$watcher" ]; then kill "$watcher" 2>/dev/null || true; wait "$watcher" 2>/dev/null || true; fi
  rm -f "$work/module.prop" "$work/bin/firewall.sh" "$work/mock/"*
  for file in firewall.lock process-lease watch-owner target-uid status.json status-revision observed-exit ipv4; do
    rm -f "$work/data/$file"
  done
  rmdir "$work/bin" "$work/mock" "$work/data" "$work"
}
trap cleanup EXIT

mkdir "$work/bin" "$work/mock" "$work/data"
touch "$work/module.prop"
export TEST_DATA_DIR="$work/data"
source=${1:-"$(dirname "$0")/../module/bin/firewall.sh"}
sed 's|^DATA_DIR=.*|DATA_DIR="$TEST_DATA_DIR"|' "$source" > "$work/bin/firewall.sh"

# Exercise the module state machine with real PIDs, without touching the host firewall.
shell=$(command -v sh)
printf '#!%s\n%s\n' "$shell" '[ "${2:-}" != -S ] || printf "%s\n" "-A CP_CLAUDE -j REJECT"' > "$work/mock/iptables"
cp "$work/mock/iptables" "$work/mock/ip6tables"
printf '#!%s\n%s\n' "$shell" 'cat > "$TEST_DATA_DIR/ipv4"' > "$work/mock/iptables-restore"
chmod 700 "$work/mock/"*
export PATH="$work/mock:$PATH"
run() {
  action=$1
  shift
  sh "$work/bin/firewall.sh" "$action" 10219 "$@"
}

wait_for() {
  n=0
  while [ "$n" -lt 50 ]; do
    if "$@"; then return 0; fi
    sleep 0.1
    n=$((n + 1))
  done
  printf 'Timed out: %s\n' "$*" >&2
  return 1
}

run block boot
sh "$work/bin/firewall.sh" watch 10219 &
watcher=$!
wait_for test -s "$TEST_DATA_DIR/watch-owner"

sleep 120 &
app=$!
run probe "$app" 8.8.4.4
run observed 8.8.4.4 JP Asia/Tokyo
run allow 8.8.4.4 wlan0 JP Asia/Tokyo en-US "$app"
grep -q '"state":"allowed"' "$TEST_DATA_DIR/status.json"
grep -q -- '-o wlan0 -j RETURN' "$TEST_DATA_DIR/ipv4"
kill -KILL "$app"
wait "$app" 2>/dev/null || true
app=
wait_for grep -q '"reason":"process_exited"' "$TEST_DATA_DIR/status.json"
if grep -q -- '-j RETURN' "$TEST_DATA_DIR/ipv4"; then exit 1; fi
grep -q '"appliedTimezone":"Asia/Tokyo"' "$TEST_DATA_DIR/status.json"

sleep 120 &
app=$!
run block startup
run probe "$app" 8.8.4.4
run observed 8.8.4.4 JP Asia/Tokyo
run allow 8.8.4.4 wlan0 JP Asia/Tokyo en-US "$app"
grep -q '"state":"allowed"' "$TEST_DATA_DIR/status.json"
run block background
test ! -e "$TEST_DATA_DIR/process-lease"
if grep -q -- '-j RETURN' "$TEST_DATA_DIR/ipv4"; then exit 1; fi
echo 'Process death revokes the rule; a new process can obtain its own lease.'
