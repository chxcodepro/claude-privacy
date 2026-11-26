#!/system/bin/sh
set -eu

MODDIR=${0%/*/*}
. "$MODDIR/bin/targets.sh"
explicit_target=${CP_TARGET:-}
target_context "${CP_TARGET:-com.anthropic.claude}"
CONFIG="$DATA_ROOT/config.json"
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
  defaults)
    cat "$DEFAULTS"
    ;;
  set|report|import-config)
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
    if [ "$1" = import-config ]; then
      current_user
      for package in $TARGET_PACKAGES; do
        uid=$(target_uid "$package" "$user")
        [ -n "$uid" ] || continue
        CP_TARGET="$package" sh "$MODDIR/bin/firewall.sh" block "$uid" startup
      done
    fi
    mv -f "$temp" "$target"
    trap - EXIT HUP INT TERM
    echo '{"ok":true}'
    ;;
  status)
    # Settings show the most recently updated gate; bridge callers select their own app.
    if [ -z "$explicit_target" ]; then
      if [ "$DATA_ROOT/aibo/status.json" -nt "$STATUS" ]; then STATUS="$DATA_ROOT/aibo/status.json"; fi
    fi
    if [ -f "$STATUS" ]; then cat "$STATUS"; else echo '{"state":"unconfigured"}'; fi
    ;;
  lookup-ip)
    if [ ! -x /system/bin/curl ]; then
      echo '设备缺少系统 curl，请手动输入公网 IPv4。' >&2
      exit 1
    fi
    if trace=$(/system/bin/curl -q -4 -fs --noproxy '*' --connect-timeout 4 --max-time 8 \
        --max-filesize 4096 -H 'Cache-Control: no-store' 'https://claude.ai/cdn-cgi/trace'); then
      :
    else
      case "$?" in
        6) echo '无法解析 claude.ai，请检查当前网络的 DNS。' >&2 ;;
        7|28) echo '连接 claude.ai:443 失败或超时，请检查当前网络或软路由所用节点。' >&2 ;;
        *) echo 'Claude 服务预检失败，请稍后重试或手动输入公网 IPv4。' >&2 ;;
      esac
      exit 1
    fi
    if ! printf '%s\n' "$trace" | awk '
      { sub(/\r$/, "") }
      /^h=/{host=substr($0,3); hosts++}
      /^ip=/{ip=substr($0,4); addresses++}
      /^loc=/{region=substr($0,5); regions++}
      END {
        if(hosts != 1 || host != "claude.ai" || addresses != 1 || ip !~ /^[0-9.]+$/) exit 1
        printf "{\"ip\":\"%s\"", ip
        if(regions == 1 && region ~ /^[A-Z][A-Z]$/) printf ",\"countryCode\":\"%s\"", region
        print "}"
      }'; then
      echo 'Claude 服务未回显有效 IPv4。' >&2
      exit 1
    fi
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
  watch-status)
    current_user
    found=0
    for package in $TARGET_PACKAGES; do
      uid=$(target_uid "$package" "$user")
      [ -n "$uid" ] || continue
      found=1
      state=$(CP_TARGET="$package" sh "$MODDIR/bin/firewall.sh" watch-status)
      if [ "$state" != running ]; then echo stopped; exit 0; fi
    done
    if [ "$found" -eq 1 ]; then echo running; else echo stopped; fi
    ;;
  watch-start)
    sh "$MODDIR/bin/diagnostics.sh" record manual_start_requested || true
    current_user
    found=0
    for package in $TARGET_PACKAGES; do
      uid=$(target_uid "$package" "$user")
      [ -n "$uid" ] || continue
      found=1
      CP_TARGET="$package" sh "$MODDIR/bin/firewall.sh" ensure-watch "$uid"
    done
    [ "$found" -eq 1 ] || { echo '当前用户未安装 Claude 或 Claude Duet。' >&2; exit 1; }
    sh "$MODDIR/bin/diagnostics.sh" record manual_start_ready || true
    ;;
  diagnostics)
    sh "$MODDIR/bin/diagnostics.sh" export
    ;;
  export-diagnostics)
    current_user
    version=$(pm list packages --user "$user" --show-versioncode dev.chx.claudeprivacy | sed -n 's/^package:dev\.chx\.claudeprivacy versionCode://p' | head -n 1)
    case "$version" in ''|*[!0-9]*) version=0 ;; esac
    [ "$version" -ge 13 ] || { echo '请先更新 Hook，再导出诊断日志。' >&2; exit 1; }
    output=$(am start --user "$user" -n dev.chx.claudeprivacy/.ExportDiagnosticsActivity 2>&1) || { echo '日志导出界面启动失败，请更新 Hook 后重试。' >&2; exit 1; }
    case "$output" in *Error:*|*Exception*) echo '日志导出界面启动失败，请更新 Hook 后重试。' >&2; exit 1 ;; esac
    ;;
  export-config|import-config-file)
    current_user
    version=$(pm list packages --user "$user" --show-versioncode dev.chx.claudeprivacy | sed -n 's/^package:dev\.chx\.claudeprivacy versionCode://p' | head -n 1)
    case "$version" in ''|*[!0-9]*) version=0 ;; esac
    minimum=16
    [ "$1" != import-config-file ] || minimum=17
    [ "$version" -ge "$minimum" ] || { echo '请先更新 Hook，再导入或导出配置。' >&2; exit 1; }
    operation=export
    [ "$1" != import-config-file ] || operation=import
    output=$(am start --user "$user" -n dev.chx.claudeprivacy/.ConfigTransferActivity --es operation "$operation" 2>&1) || { echo '文件选择界面启动失败，请更新 Hook 后重试。' >&2; exit 1; }
    case "$output" in *Error:*|*Exception*) echo '文件选择界面启动失败，请更新 Hook 后重试。' >&2; exit 1 ;; esac
    ;;
  install-hook)
    pm install -r "$MODDIR/hook.apk"
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
    echo 'Usage: control.sh get|defaults|set BASE64|report BASE64|import-config BASE64|export-config|import-config-file|status|lookup-ip|release|hook-status|watch-status|watch-start|diagnostics|export-diagnostics|install-hook|diagnose-hook|more|download-update VERSION' >&2
    exit 2
    ;;
esac
