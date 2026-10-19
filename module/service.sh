#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"
# The UI writes only next-boot intent. Never apply a pending switch mid-boot.
if [ "$(getprop sys.ipriv.enabled)" = 0 ]; then
  # Package Manager is not ready during post-fs-data. Retry restoring only our
  # recorded package changes after boot; do not leave Find Device disabled.
  . "$MODDIR/guard.sh"
  while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done
  guard_cleanup
  exit 0
fi
ensure_privacy_config
load_privacy_config

# qcril and init may repopulate properties after this service starts. KernelSU
# resetprop's wait mode gives each key an event-driven guard with no polling gap.
apply_imei_mask
sh "$MODDIR/guard.sh" run &
start_imei_watchers
