#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"
. "$MODDIR/guard.sh"
set_masked_prop sys.ipriv.enabled "$IMEI_PRIVACY_ENABLED" || exit 1
set_masked_prop sys.ipriv.boot_ready 1 || exit 1
if [ "$IMEI_PRIVACY_ENABLED" = 0 ]; then
  guard_cleanup
  exit 0
fi
ensure_privacy_config
load_privacy_config
apply_imei_mask
rm -f "$GUARD_DIR/stopping"
# Block before normal application startup; the long-running supervisor renews
# the challenge and may allow traffic only after all current process leases pass.
guard_boot || :
