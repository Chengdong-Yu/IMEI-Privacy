#!/system/bin/sh
# Root-only, fixed command interface for the module's own UI. No identifiers.
MODDIR=${0%/*}
. "$MODDIR/common.sh"
. "$MODDIR/guard.sh"
[ "$(id -u)" = 0 ] || exit 77
case "${1:-status}" in set-enabled) control_user=${3:-0};; *) control_user=${2:-0};; esac
guard_number "$control_user" && [ "$control_user" -le 21473 ] || { printf 'error=INVALID_USER\n'; exit 2; }

control_scope_status() {
  control_apk="$MODDIR/system/app/ImeiPrivacy/ImeiPrivacy.apk"
  if [ -f "$control_apk" ] && command -v app_process >/dev/null 2>&1; then
    if control_scope_output=$(CLASSPATH="$control_apk" timeout 8 app_process /system/bin \
        com.richard.imeiprivacy.ScopeInspector "$control_user" 2>/dev/null) &&
       [ -n "$control_scope_output" ]; then
      printf '%s\n' "$control_scope_output"
      return
    fi
  fi
  printf 'scope_state=UNKNOWN\nscope_reason=INSPECTOR_UNAVAILABLE\nscope_user=%s\nscope_checked_uptime=%s\n' \
    "$control_user" "$(guard_now)"
}

control_status() {
  load_ui_settings
  printf 'protocol=1\npending_enabled=%s\n' "$IMEI_PRIVACY_ENABLED"
  control_version=$(sed -n 's/^version=//p' "$MODDIR/module.prop")
  case "$control_version" in ''|*[!0-9.]*) control_version=unknown;; esac
  printf 'module_version=%s\n' "$control_version"
  control_active=$(getprop sys.ipriv.enabled)
  case "$control_active" in 0|1) ;; *) control_active=unknown;; esac
  printf 'active_enabled=%s\n' "$control_active"
  control_boot=$(getprop sys.ipriv.boot_ready)
  [ "$control_boot" = 1 ] || control_boot=0
  printf 'boot_ready=%s\n' "$control_boot"
  control_disabled=0
  [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || control_disabled=1
  printf 'module_disabled=%s\n' "$control_disabled"
  control_state=UNKNOWN; control_reason=SUPERVISOR_NOT_READY
  control_checked=0; control_processes=0; control_verified=0; control_match=0
  if [ -f "$GUARD_DIR/status" ] && [ ! -L "$GUARD_DIR/status" ]; then
    while IFS='=' read -r control_key control_value; do
      case "$control_key" in
        state) case "$control_value" in ALLOWED|BLOCKED|STOPPED|ERROR|DISABLED) control_state=$control_value;; esac;;
        reason) case "$control_value" in ''|*[!A-Z_]*) ;; *) [ "${#control_value}" -le 96 ] && control_reason=$control_value;; esac;;
        checked_uptime) guard_number "$control_value" && control_checked=$control_value;;
        processes) guard_number "$control_value" && control_processes=$control_value;;
        verified) guard_number "$control_value" && control_verified=$control_value;;
        challenge) [ -n "$control_value" ] && [ "$control_value" = "$(getprop persist.sys.ipriv.guard_nonce)" ] && control_match=1;;
      esac
    done < "$GUARD_DIR/status"
  fi
  printf 'guard_state=%s\nreason=%s\nchecked_uptime=%s\nnonce_match=%s\nprocesses=%s\nverified=%s\n' \
    "$control_state" "$control_reason" "$control_checked" "$control_match" "$control_processes" "$control_verified"
  control_scope_status
}

case "${1:-status}" in
  status) control_status;;
  set-enabled)
    case "${2:-}" in 0|1) ;; *) printf 'error=INVALID_SETTING\n'; exit 2;; esac
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || { printf 'error=MODULE_DISABLED\n'; exit 3; }
    umask 077
    mkdir -p "$STATE_DIR" && chmod 0700 "$STATE_DIR" || exit 4
    [ ! -L "$UI_SETTINGS" ] && [ ! -d "$UI_SETTINGS" ] || exit 4
    control_temp="$UI_SETTINGS.tmp.$$"
    if ! printf 'IMEI_PRIVACY_ENABLED=%s\n' "$2" > "$control_temp" ||
       ! chmod 0600 "$control_temp" || ! mv -f "$control_temp" "$UI_SETTINGS"; then
      rm -f "$control_temp"
      printf 'error=SETTING_WRITE_FAILED\n'
      exit 4
    fi
    control_status;;
  recheck)
    [ "$(getprop sys.ipriv.enabled)" = 1 ] && [ "$(getprop sys.ipriv.boot_ready)" = 1 ] &&
      [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || { printf 'error=RECHECK_UNAVAILABLE\n'; exit 3; }
    sh "$MODDIR/action.sh" recheck >/dev/null 2>&1 || { printf 'error=RECHECK_FAILED\n'; exit 5; }
    control_status;;
  reboot-confirmed)
    # Reached only from an explicit UI confirmation or a deliberate root CLI call.
    sync
    if svc power reboot >/dev/null 2>&1; then
      printf 'reboot_requested=1\n'
    else
      printf 'error=REBOOT_FAILED\n'
      exit 6
    fi;;
  *) printf 'error=UNKNOWN_COMMAND\n'; exit 2;;
esac
