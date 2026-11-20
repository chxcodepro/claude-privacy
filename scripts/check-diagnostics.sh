#!/bin/sh
set -eu
work=$(mktemp -d)
cleanup() {
  rm -f "$work/bin/diagnostics.sh" "$work/bin/firewall.sh" "$work/mock/"* "$work/data/"* "$work/module.prop" "$work/report"
  rmdir "$work/bin" "$work/mock" "$work/data" "$work"
}
trap cleanup EXIT
mkdir "$work/bin" "$work/mock" "$work/data"
export TEST_DATA_DIR="$work/data"
source=${1:-"$(dirname "$0")/../module/bin/diagnostics.sh"}
sed -e 's|^DATA_DIR=.*|DATA_DIR="$TEST_DATA_DIR"|' -e 's|/system/bin/||g' "$source" > "$work/bin/diagnostics.sh"
shell=$(command -v sh)
# Windows has no Android flock/getprop/logcat; these mocks cover formatting and data minimization only.
printf '#!%s\nexit 0\n' "$shell" > "$work/mock/flock"
printf '#!%s\necho 34\n' "$shell" > "$work/mock/getprop"
printf '#!%s\n%s\n' "$shell" 'case "$*" in *com.anthropic.claude) echo package:com.anthropic.claude versionCode:26111719 ;; *) echo package:dev.chx.claudeprivacy versionCode:14 ;; esac' > "$work/mock/cmd"
printf '#!%s\necho stopped\n' "$shell" > "$work/bin/firewall.sh"
printf '#!%s\nprintf "%%s\\n" "cpdiag t=123 pid=45 bridge_failed method=probe code=watchdog_unavailable" "cpdiag token=SECRET.VALUE" "other-tag COOKIE_SECRET"\n' "$shell" > "$work/mock/logcat"
chmod 700 "$work/mock/"*
export PATH="$work/mock:$PATH"
printf 'version=0.1.15-dev\nversionCode=17\n' > "$work/module.prop"
printf '%s\n' '{"state":"blocked","reason":"watchdog_unavailable","ip":"8.8.4.4","detail":"COOKIE_SECRET","updatedAt":123,"revision":4}' > "$work/data/status.json"
printf '%s\n' 'LOGIN_SECRET' > "$work/data/config.json"
run() { sh "$work/bin/diagnostics.sh" "$@"; }
run record watch_started owner=123
if run record leaked 8.8.4.4; then echo 'Accepted an IP in diagnostic events' >&2; exit 1; fi
if run record 'cookie=SECRET.VALUE'; then exit 1; fi
run export > "$work/report"
grep -qx reason=watchdog_unavailable "$work/report"
grep -qx claude_version_code=26111719 "$work/report"
grep -qx hook_version_code=14 "$work/report"
grep -q 'watch_started owner=123' "$work/report"
grep -q 'bridge_failed method=probe' "$work/report"
if grep -Eq '8\.8\.4\.4|COOKIE_SECRET|LOGIN_SECRET|SECRET\.VALUE' "$work/report"; then exit 1; fi
dd if=/dev/zero bs=65536 count=1 2>/dev/null > "$work/data/diagnostics.log"
run record watch_restarted
test -f "$work/data/diagnostics.log.1"
test "$(wc -c < "$work/data/diagnostics.log")" -lt 300
echo 'Diagnostic event validation, private export and bounded rotation passed.'
