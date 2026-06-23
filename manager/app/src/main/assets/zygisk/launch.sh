#!/system/bin/sh
#
# Zygisk-Ultima built-in launcher. Deployed to /data/adb/post-fs-data.d/ ONLY
# while Zygisk is enabled (this file's presence is the on/off switch + the
# recovery kill-switch: delete it to disable). ksud runs post-fs-data.d at boot.
#
# Adapted from ReZygisk's post-fs-data.sh (GPL-3.0, The PerformanC Organization).
#
ZDIR=/data/adb/ksu/zygisk
LOG="$ZDIR/boot.log"
echo "--- $(date) launch.sh start ---" >> "$LOG" 2>/dev/null

# Off-switch / safety: only run if the engine is present and enabled.
[ -f "$ZDIR/enable" ] || { echo "no enable flag, skip" >> "$LOG" 2>/dev/null; exit 0; }
[ -d "$ZDIR/bin" ] || { echo "no bin dir, skip" >> "$LOG" 2>/dev/null; exit 0; }

cd "$ZDIR" || exit 1

# sepolicy isn't a module, so ksud won't auto-load it at boot - re-apply here.
if [ -f "$ZDIR/payload/sepolicy.rule" ]; then
  /data/adb/ksu/bin/ksud sepolicy apply "$ZDIR/payload/sepolicy.rule" >> "$LOG" 2>&1
  echo "$(date) sepolicy applied (exit $?)" >> "$LOG" 2>/dev/null
fi

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
  *arm64-v8a*|*x86_64*) BIN=./bin/zygisk-ptrace64 ;;
  *)                    BIN=./bin/zygisk-ptrace32 ;;
esac

# Fully detach so the monitor survives after ksud's script runner returns.
if [ -x "$BIN" ]; then
  echo "$(date) launching $BIN monitor (setsid)" >> "$LOG" 2>/dev/null
  setsid "$BIN" monitor >> "$LOG" 2>&1 &
  echo "$(date) launched (pid $!)" >> "$LOG" 2>/dev/null
else
  echo "$(date) ERROR: $BIN not executable" >> "$LOG" 2>/dev/null
fi

exit 0
