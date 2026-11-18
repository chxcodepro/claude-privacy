#!/system/bin/sh
set -eu

MODDIR=${0%/*/*}
DATA_DIR=/data/adb/claude_privacy
CONFIG="$DATA_DIR/config.json"
DEFAULTS="$MODDIR/default-config.json"
STATUS="$DATA_DIR/status.json"
mkdir -p "$DATA_DIR"
chmod 0700 "$DATA_DIR"

current_user() {
  user=$(am get-current-user)
  case "$user" in ''|*[!0-9]*) echo '无法确定当前 Android 用户' >&2; exit 1 ;; esac
}

case "${1:-}" in
  get)
    if [ -f "$CONFIG" ]; then cat "$CONFIG"; else cat "$DEFAULTS"; fi
    ;;
  set|report)
    target=$CONFIG
    [ "$1" != report ] || target=$STATUS
    encoded=${2:-}
    case "$encoded" in
      ''|*[!A-Za-z0-9+/=]*) echo 'Invalid settings payload' >&2; exit 2 ;;
    esac
    umask 077
    temp="$target.$$"
    trap 'rm -f "$temp"' EXIT HUP INT TERM
    if command -v base64 >/dev/null 2>&1; then
      printf '%s' "$encoded" | base64 -d > "$temp"
    else
      printf '%s' "$encoded" | /data/adb/ksu/bin/busybox base64 -d > "$temp"
    fi
    size=$(wc -c < "$temp")
    [ "$size" -ge 2 ] && [ "$size" -le 16384 ] || { echo 'Settings payload size invalid' >&2; exit 2; }
    [ "$(head -c 1 "$temp")" = '{' ] || { echo 'Settings payload invalid' >&2; exit 2; }
    mv -f "$temp" "$target"
    trap - EXIT HUP INT TERM
    echo '{"ok":true}'
    ;;
  status)
    if [ -f "$STATUS" ]; then cat "$STATUS"; else echo '{"state":"unconfigured"}'; fi
    ;;
  release)
    getprop ro.build.version.release
    ;;
  hook-status)
    current_user
    version=$(pm list packages --user "$user" --show-versioncode dev.chx.claudeprivacy | sed -n 's/^package:dev\.chx\.claudeprivacy versionCode://p' | head -n 1)
    case "$version" in ''|*[!0-9]*) version=0 ;; esac
    printf '{"versionCode":%s}\n' "$version"
    ;;
  install-hook)
    pm install -r "$MODDIR/hook.apk"
    ;;
  lookup-claude-ip)
    if [ ! -x /system/bin/curl ]; then
      echo '设备缺少系统 curl，无法执行 IPv4 查询。可手动添加公网 IPv4。' >&2
      exit 1
    fi
    # Use the same trace endpoint as Net.Coffee, forcing IPv4.
    /system/bin/curl -4 -fsS --connect-timeout 4 --max-time 8 --max-filesize 16384 -H 'Cache-Control: no-cache' https://claude.ai/cdn-cgi/trace
    ;;
  diagnose-hook)
    current_user
    # A process started before the grant may still have the old mount namespace.
    am kill --user "$user" dev.chx.claudeprivacy >/dev/null 2>&1 || true
    output=$(content call --user "$user" --uri content://dev.chx.claudeprivacy.bridge --method health 2>&1) || { printf '%s\n' "$output" >&2; exit 1; }
    case "$output" in
      'Result: Bundle[{result=0}]') ;;
      *'error=su_unavailable'*) echo 'Hook 无法运行 su。请在 SukiSU Ultra → 超级用户中授权 Claude Privacy Hook，再重试。' >&2; exit 1 ;;
      'Result: Bundle[{}]') echo 'Hook 返回空响应。请更新 Hook 后重试；若仍报错，请检查其 SukiSU Root 授权。' >&2; exit 1 ;;
      *) printf 'Hook 检查失败：%s\n' "$output" >&2; exit 1 ;;
    esac
    ;;
  more)
    am start -a android.intent.action.VIEW -d 'https://ip.net.coffee/claude/' >/dev/null
    echo '{"ok":true}'
    ;;
  download-update)
    version=${2:-}
    if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
      echo '更新版本格式无效' >&2
      exit 1
    fi
    am start --user current -a android.intent.action.VIEW -d "https://github.com/chxcodepro/claude-privacy/releases/download/v$version/claude-privacy-$version-dev.zip"
    ;;
  *)
    echo 'Usage: control.sh get|set BASE64|report BASE64|status|release|hook-status|install-hook|lookup-claude-ip|diagnose-hook|more|download-update VERSION' >&2
    exit 2
    ;;
esac
