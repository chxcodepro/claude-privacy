#!/system/bin/sh

DATA_ROOT=/data/adb/claude_privacy
TARGET_PACKAGES='com.anthropic.claude com.anthropic.aibo'

target_context() {
  TARGET_PACKAGE=$1
  DATA_DIR=$DATA_ROOT
  CHAIN=CP_CLAUDE
  case "$TARGET_PACKAGE" in
    com.anthropic.claude) ;;
    com.anthropic.aibo) DATA_DIR="$DATA_ROOT/aibo"; CHAIN=CP_AIBO ;;
    *) echo 'Unknown target package' >&2; return 2 ;;
  esac
  CP_TARGET=$TARGET_PACKAGE
  export CP_TARGET
}

target_uid() {
  cmd package list packages --user "$2" -U "$1" 2>/dev/null |
    awk -v package="package:$1" '$1 == package && $2 ~ /^uid:[0-9]+$/ {sub(/^uid:/, "", $2); print $2; exit}'
}
