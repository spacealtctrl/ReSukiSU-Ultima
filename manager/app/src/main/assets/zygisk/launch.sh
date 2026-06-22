#!/system/bin/sh
#
# Zygisk-Ultima built-in launcher. Deployed to /data/adb/post-fs-data.d/ ONLY
# while Zygisk is enabled (this file's presence is the on/off switch + the
# recovery kill-switch: delete it to disable). ksud runs post-fs-data.d at boot.
#
# Adapted from ReZygisk's post-fs-data.sh (GPL-3.0, The PerformanC Organization).
#
ZDIR=/data/adb/ksu/zygisk

# Off-switch / safety: only run if the engine is present and enabled.
[ -f "$ZDIR/enable" ] || exit 0
[ -d "$ZDIR/bin" ] || exit 0

cd "$ZDIR"

# Working dir expected by the engine.
export TMP_PATH=/data/adb/rezygisk
rm -rf "$TMP_PATH"
mkdir -p "$TMP_PATH"
chmod 555 "$TMP_PATH"
chcon u:object_r:system_file:s0 "$TMP_PATH" 2>/dev/null || true

CPU_ABIS_PROP1=$(getprop ro.system.product.cpu.abilist)
CPU_ABIS_PROP2=$(getprop ro.product.cpu.abilist)
if [ "${#CPU_ABIS_PROP2}" -gt "${#CPU_ABIS_PROP1}" ]; then
  CPU_ABIS=$CPU_ABIS_PROP2
else
  CPU_ABIS=$CPU_ABIS_PROP1
fi

case "$CPU_ABIS" in
  *arm64-v8a*|*x86_64*) [ -x ./bin/zygisk-ptrace64 ] && ./bin/zygisk-ptrace64 monitor & ;;
  *)                    [ -x ./bin/zygisk-ptrace32 ] && ./bin/zygisk-ptrace32 monitor & ;;
esac

exit 0
