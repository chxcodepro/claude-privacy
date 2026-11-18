#!/system/bin/sh
MODDIR=${0%/*}
if [ ! -f "$MODDIR/hook.apk" ]; then
  echo 'Hook APK missing from module package'
  exit 1
fi
pm install -r "$MODDIR/hook.apk" || exit 1
echo 'Hook APK installed. Enable its Claude scope in LSPosed and grant it root in SukiSU.'
