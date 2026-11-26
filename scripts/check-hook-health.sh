#!/bin/sh
set -eu

control="$(dirname "$0")/../module/bin/control.sh"
if [ "$#" -gt 0 ]; then control=$1; fi
work=$(mktemp -d)
trap 'rm -f "$work/bin/control.sh" "$work/bin/targets.sh" "$work/am" "$work/content"; rmdir "$work/bin" "$work/state" "$work"' EXIT
mkdir "$work/state" "$work/bin"
cp "$control" "$work/bin/control.sh"
sed "s|^DATA_ROOT=.*|DATA_ROOT='$work/state'|" "$(dirname "$control")/targets.sh" > "$work/bin/targets.sh"
printf '#!/bin/sh\necho 0\n' > "$work/am"
# Simulate Android's content command; exercise the actual module script.
printf '#!/bin/sh\nprintf "%%s\\n" "$CONTENT_REPLY"\n' > "$work/content"
chmod +x "$work/am" "$work/content"
export PATH="$work:$PATH"

check() {
  actual=0
  output=$(CONTENT_REPLY="$1" sh "$work/bin/control.sh" diagnose-hook 2>&1) || actual=$?
  [ "$actual" -eq "$2" ] && case "$output" in *"$3"*) return ;; esac
  printf 'Reply %s produced exit %s: %s\n' "$1" "$actual" "$output" >&2
  exit 1
}

check 'Result: Bundle[{result=0}]' 0 ''
check 'Result: Bundle[{}]' 1 '空响应'
check 'Result: Bundle[{detail=su unavailable, error=su_unavailable}]' 1 '授权 Claude Privacy Hook'
check 'Result: Bundle[{result=01}]' 1 '检查失败'
