#!/bin/sh
set -eu

work=$(mktemp -d)
watcher= supervisor= app=
cleanup() {
  rm -f "$work/module.prop"
  if [ -n "$app" ]; then kill "$app" 2>/dev/null || true; wait "$app" 2>/dev/null || true; fi
  if [ -n "$supervisor" ]; then kill -KILL "$supervisor" 2>/dev/null || true; fi
  if [ -n "$watcher" ]; then kill "$watcher" 2>/dev/null || true; wait "$watcher" 2>/dev/null || true; fi
  rm -f "$work/bin/firewall.sh" "$work/bin/targets.sh" "$work/mock/"* "$work/data/aibo/"*
  for file in firewall.lock supervisor.lock watch.lock watch.log process-lease watch-owner target-uid status.json status-revision observed-exit ipv4 test-app bridge-prepared; do
    rm -f "$work/data/$file"
  done
  rmdir "$work/bin" "$work/mock" "$work/data/aibo" "$work/data" "$work"
}
trap cleanup EXIT

mkdir "$work/bin" "$work/mock" "$work/data"
mkdir "$work/data/aibo"
touch "$work/module.prop"
export TEST_DATA_DIR="$work/data"
source=${1:-"$(dirname "$0")/../module/bin/firewall.sh"}
sed -e 's|^DATA_DIR=.*|DATA_DIR="$TEST_DATA_DIR"|' -e "s|/system/bin/content|$work/mock/content|" "$source" > "$work/bin/firewall.sh"
sed 's|^DATA_ROOT=.*|DATA_ROOT="$TEST_DATA_DIR"|' "$(dirname "$source")/targets.sh" > "$work/bin/targets.sh"

# Exercise the module state machine with real PIDs, without touching the host firewall.
shell=$(command -v sh)
printf '#!%s\n%s\n' "$shell" '[ "${2:-}" != -S ] || printf "%s\n" "-A CP_CLAUDE -j REJECT"' > "$work/mock/iptables"
cp "$work/mock/iptables" "$work/mock/ip6tables"
printf '#!%s\n%s\n' "$shell" 'case "${CP_TARGET:-}" in com.anthropic.aibo) cat > "$TEST_DATA_DIR/aibo/ipv4" ;; *) cat > "$TEST_DATA_DIR/ipv4" ;; esac' > "$work/mock/iptables-restore"
printf '#!%s\n%s\n' "$shell" 'cat "$TEST_DATA_DIR/test-app" 2>/dev/null' > "$work/mock/pidof"
printf '#!%s\n%s\n' "$shell" 'echo prepare >> "$TEST_DATA_DIR/bridge-prepared"; echo "Result: Bundle[{result=ready}]"' > "$work/mock/content"
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
test "$(run watch-status)" = stopped

sleep 120 &
app=$!
if run probe "$app" 8.8.4.4 2>"$work/data/watch.log"; then exit 1; fi
grep -qx WATCHDOG_UNAVAILABLE "$work/data/watch.log"
run ensure-watch
printf '%s\n' "$app" > "$TEST_DATA_DIR/test-app"
wait_for test -s "$TEST_DATA_DIR/bridge-prepared"
run probe "$app" 8.8.4.4
test "$(run watch-status)" = running
read -r watcher _ < "$TEST_DATA_DIR/watch-owner"
supervisor=$(awk '/^PPid:/{print $2}' "/proc/$watcher/status")
case "$supervisor" in ''|0|1|*[!0-9]*) exit 1 ;; esac
run observed 8.8.4.4 JP Asia/Tokyo
run allow 8.8.4.4 wlan0 JP Asia/Tokyo en-US "$app"
grep -q '"state":"allowed"' "$TEST_DATA_DIR/status.json"
grep -q -- '-o wlan0 -m time --datestop .* -j RETURN' "$TEST_DATA_DIR/ipv4"
CP_TARGET=com.anthropic.aibo sh "$work/bin/firewall.sh" block 10220 startup
test "$(CP_TARGET=com.anthropic.aibo sh "$work/bin/firewall.sh" watch-status)" = stopped
test "$(run watch-status)" = running
test -s "$TEST_DATA_DIR/process-lease"
grep -q '"state":"allowed"' "$TEST_DATA_DIR/status.json"
grep -q -- '-A CP_AIBO -j REJECT' "$TEST_DATA_DIR/aibo/ipv4"
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
old_watch=$watcher
kill -KILL "$watcher"
watch_replaced() {
  test "$(run watch-status)" = running &&
    read -r watcher _ < "$TEST_DATA_DIR/watch-owner" &&
    [ "$watcher" != "$old_watch" ]
}
wait_for watch_replaced
test ! -e "$TEST_DATA_DIR/process-lease"
if grep -q -- '-j RETURN' "$TEST_DATA_DIR/ipv4"; then exit 1; fi

run probe "$app" 8.8.4.4
run observed 8.8.4.4 JP Asia/Tokyo
run allow 8.8.4.4 wlan0 JP Asia/Tokyo en-US "$app"
kill -KILL "$supervisor" "$watcher"
watcher= supervisor=
watch_stopped() { test "$(run watch-status)" = stopped; }
wait_for watch_stopped
grep -q -- '--datestop .* -j RETURN' "$TEST_DATA_DIR/ipv4"
run ensure-watch
test "$(run watch-status)" = running
read -r watcher _ < "$TEST_DATA_DIR/watch-owner"
supervisor=$(awk '/^PPid:/{print $2}' "/proc/$watcher/status")
test ! -e "$TEST_DATA_DIR/process-lease"
if grep -q -- '-j RETURN' "$TEST_DATA_DIR/ipv4"; then exit 1; fi
echo 'Manual start, process exit, supervisor restart, expiry rule and fail-closed restart passed.'
