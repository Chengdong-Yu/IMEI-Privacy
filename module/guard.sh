#!/system/bin/sh

# Root-owned fail-closed UID supervisor. No app-controlled file is ever sourced.
GUARD_PACKAGE=com.xiaomi.finddevice
GUARD_DIR=/data/adb/imei_privacy/guard
GUARD_PACKAGES=/data/system/packages.list
GUARD_USERS=/data/system/users
GUARD_PROC=/proc
GUARD_CHAIN=IPRIV_FD
GUARD_GATE=IPRIV_FD_GATE
GUARD_TTL=15
GUARD_IP4=iptables
GUARD_IP6=ip6tables
GUARD_UIDS=""
GUARD_USER_IDS=""
GUARD_NONCE=""
GUARD_READY=0
GUARD_MODE=BLOCKED
GUARD_REASON=STARTING
GUARD_PROCESS_COUNT=0
GUARD_VERIFIED_COUNT=0

guard_now() { read -r guard_clock _ < "$GUARD_PROC/uptime"; printf '%s\n' "${guard_clock%%.*}"; }
guard_number() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "${#1}" -le 10 ]; }
guard_ticks() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "${#1}" -le 20 ]; }

guard_prepare() {
  umask 077
  mkdir -p "$GUARD_DIR" || return 1
  chmod 0700 "$GUARD_DIR" || return 1
  GUARD_IP4=$(command -v iptables) || return 1
  GUARD_IP6=$(command -v ip6tables) || return 1
}

guard_publish_property() { set_masked_prop "$1" "$2"; }

guard_challenge() {
  GUARD_NONCE=$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')
  case "$GUARD_NONCE" in *[!0-9a-f]*|'') return 1;; esac
  [ "${#GUARD_NONCE}" -eq 32 ] || return 1
  printf '%s\n' "$GUARD_NONCE" > "$GUARD_DIR/nonce.tmp" || return 1
  mv -f "$GUARD_DIR/nonce.tmp" "$GUARD_DIR/nonce" || return 1
  guard_publish_property persist.sys.ipriv.guard_nonce "$GUARD_NONCE"
}

guard_discover_users() {
  GUARD_USER_IDS=""
  for guard_xml in "$GUARD_USERS"/[0-9]*.xml; do
    [ -f "$guard_xml" ] || continue
    guard_user=${guard_xml##*/}; guard_user=${guard_user%.xml}
    guard_number "$guard_user" || continue
    [ "$guard_user" -le 21473 ] || continue
    GUARD_USER_IDS="$GUARD_USER_IDS $guard_user"
  done
  [ -n "$GUARD_USER_IDS" ] || GUARD_USER_IDS=" 0"
}

guard_resolve_uids() {
  GUARD_UIDS=""
  guard_discover_users
  [ -r "$GUARD_PACKAGES" ] || { GUARD_REASON=PACKAGE_LIST_UNAVAILABLE; return 1; }
  guard_appid=$(awk -v package="$GUARD_PACKAGE" '$1 == package {print $2}' "$GUARD_PACKAGES")
  guard_number "$guard_appid" || { GUARD_REASON=TARGET_NOT_FOUND; return 1; }
  [ "$guard_appid" -gt 0 ] && [ "$guard_appid" -lt 100000 ] || { GUARD_REASON=INVALID_TARGET_UID; return 1; }
  guard_conflicts=$(awk -v package="$GUARD_PACKAGE" -v uid="$guard_appid" '$2 == uid && $1 != package {count++} END {print count+0}' "$GUARD_PACKAGES")
  [ "$guard_conflicts" -eq 0 ] || { GUARD_REASON=SHARED_UID_UNSAFE; return 1; }
  for guard_user in $GUARD_USER_IDS; do GUARD_UIDS="$GUARD_UIDS $((guard_user * 100000 + guard_appid))"; done
}

guard_family_setup() {
  guard_tool=$1
  "$guard_tool" -w 2 -N "$GUARD_GATE" >/dev/null 2>&1 || :
  if ! "$guard_tool" -w 2 -C "$GUARD_GATE" -j REJECT >/dev/null 2>&1 &&
     ! "$guard_tool" -w 2 -C "$GUARD_GATE" -j RETURN >/dev/null 2>&1; then
    "$guard_tool" -w 2 -A "$GUARD_GATE" -j REJECT >/dev/null 2>&1 || return 1
  fi
  # Atomic replace, never flush a live gate and briefly expose its UID.
  "$guard_tool" -w 2 -R "$GUARD_GATE" 1 -j REJECT >/dev/null 2>&1 || return 1
  "$guard_tool" -w 2 -N "$GUARD_CHAIN" >/dev/null 2>&1 || :
  for guard_uid in $GUARD_UIDS; do
    "$guard_tool" -w 2 -C "$GUARD_CHAIN" -m owner --uid-owner "$guard_uid" -j "$GUARD_GATE" >/dev/null 2>&1 ||
      "$guard_tool" -w 2 -A "$GUARD_CHAIN" -m owner --uid-owner "$guard_uid" -j "$GUARD_GATE" >/dev/null 2>&1 || return 1
  done
  guard_first=$("$guard_tool" -w 2 -S OUTPUT 2>/dev/null | sed -n '/^-A OUTPUT /{p;q;}')
  if [ "$guard_first" != "-A OUTPUT -j $GUARD_CHAIN" ]; then
    "$guard_tool" -w 2 -I OUTPUT 1 -j "$GUARD_CHAIN" >/dev/null 2>&1 || return 1
  fi
}

guard_remove_stale_rules() {
  [ -r "$GUARD_DIR/uids" ] || return 0
  read -r guard_old_uids < "$GUARD_DIR/uids"
  for guard_old_uid in $guard_old_uids; do
    guard_number "$guard_old_uid" || continue
    case " $GUARD_UIDS " in *" $guard_old_uid "*) continue;; esac
    for guard_tool in "$GUARD_IP4" "$GUARD_IP6"; do
      while "$guard_tool" -w 2 -C "$GUARD_CHAIN" -m owner --uid-owner "$guard_old_uid" -j "$GUARD_GATE" >/dev/null 2>&1; do
        "$guard_tool" -w 2 -D "$GUARD_CHAIN" -m owner --uid-owner "$guard_old_uid" -j "$GUARD_GATE" >/dev/null 2>&1 || break
      done
    done
  done
}

guard_gate() {
  guard_action=$1
  guard_failed=0
  "$GUARD_IP4" -w 2 -R "$GUARD_GATE" 1 -j "$guard_action" >/dev/null 2>&1 || guard_failed=1
  "$GUARD_IP6" -w 2 -R "$GUARD_GATE" 1 -j "$guard_action" >/dev/null 2>&1 || guard_failed=1
  if [ "$guard_failed" -ne 0 ]; then
    # Partial allow must never remain if the other address family failed.
    "$GUARD_IP4" -w 2 -R "$GUARD_GATE" 1 -j REJECT >/dev/null 2>&1 || :
    "$GUARD_IP6" -w 2 -R "$GUARD_GATE" 1 -j REJECT >/dev/null 2>&1 || :
    GUARD_READY=0
    guard_publish_property persist.sys.ipriv.guard_ready 0
    GUARD_REASON=FIREWALL_ERROR
    return 1
  fi
}

guard_current_setting() {
  # Only the per-user enabled enum is retained; no account/identifier data.
  timeout 5 dumpsys package "$GUARD_PACKAGE" 2>/dev/null |
    awk -v user="$1:" '$1 == "User" && $2 == user {for (i=1;i<=NF;i++) if ($i ~ /^enabled=[0-4]$/) {sub("enabled=", "", $i); print $i; exit}}'
}

guard_disable_target() {
  # Fallback for unsupported firewalls/shared UIDs, or explicit stop-on-failure.
  # Never block a shared account UID. Record the exact prior enum before disabling.
  [ -n "$GUARD_USER_IDS" ] || guard_discover_users
  guard_stop_failed=0
  for guard_user in $GUARD_USER_IDS; do
    guard_saved="$GUARD_DIR/disabled.$guard_user"
    if [ ! -f "$guard_saved" ]; then
      guard_setting=$(guard_current_setting "$guard_user")
      case "$guard_setting" in
        0|1|4)
          guard_saved_tmp="$guard_saved.tmp.$$"
          if [ -e "$guard_saved" ] || [ -L "$guard_saved" ] ||
             ! (umask 077; printf '%s\n' "$guard_setting" > "$guard_saved_tmp") ||
             ! mv -f "$guard_saved_tmp" "$guard_saved"; then
            rm -f "$guard_saved_tmp" 2>/dev/null || :
            guard_stop_failed=1
            continue
          fi;;
        2|3) timeout 5 am force-stop --user "$guard_user" "$GUARD_PACKAGE" >/dev/null 2>&1 || guard_stop_failed=1; continue;;
        *) timeout 5 am force-stop --user "$guard_user" "$GUARD_PACKAGE" >/dev/null 2>&1 || :; guard_stop_failed=1; continue;;
      esac
    fi
    if ! timeout 5 pm disable-user --user "$guard_user" "$GUARD_PACKAGE" >/dev/null 2>&1 ||
       ! timeout 5 am force-stop --user "$guard_user" "$GUARD_PACKAGE" >/dev/null 2>&1 ||
       [ "$(guard_current_setting "$guard_user")" != 3 ]; then guard_stop_failed=1; fi
  done
  [ "$guard_stop_failed" -eq 0 ]
}

guard_safe_stop() {
  if guard_disable_target; then GUARD_MODE=STOPPED;
  else GUARD_MODE=ERROR; GUARD_REASON=ENFORCEMENT_UNAVAILABLE; fi
}

guard_restore_target() {
  for guard_saved in "$GUARD_DIR"/disabled.[0-9]*; do
    [ -f "$guard_saved" ] || continue
    guard_user=${guard_saved##*.}; guard_number "$guard_user" || continue
    read -r guard_setting < "$guard_saved"
    # Restore only a package still in our disabled-user state. Respect user changes.
    guard_current=$(guard_current_setting "$guard_user")
    case "$guard_current" in 0|1|2|3|4) ;; *) continue;; esac
    if [ "$guard_current" = 3 ]; then
      case "$guard_setting" in
        0) guard_restore_command=default-state;;
        1) guard_restore_command=enable;;
        4) guard_restore_command=disable-until-used;;
        *) continue;;
      esac
      timeout 5 pm "$guard_restore_command" --user "$guard_user" "$GUARD_PACKAGE" >/dev/null 2>&1 || continue
      [ "$(guard_current_setting "$guard_user")" = "$guard_setting" ] || continue
    fi
    rm -f "$guard_saved"
  done
}

guard_root_profile_ok() {
  [ -s "$STATE_CONFIG" ] && grep -q '^PROFILE_VERSION=2$' "$STATE_CONFIG" || return 1
  for guard_key in ro.vendor.oem.imei ro.vendor.oem.imei1 persist.vendor.radio.imei persist.vendor.radio.imei1 \
    ro.ril.oem.imei ro.ril.oem.imei1 persist.radio.imei persist.radio.imei1 ro.ril.miui.imei0 persist.sys.ipriv.imei0; do
    [ "$(getprop "$guard_key")" = "$IMEI_SLOT_0" ] || return 1
  done
  for guard_key in ro.vendor.oem.imei2 persist.vendor.radio.imei2 ro.ril.oem.imei2 persist.radio.imei2 \
    ro.ril.miui.imei1 persist.sys.ipriv.imei1; do
    [ "$(getprop "$guard_key")" = "$IMEI_SLOT_1" ] || return 1
  done
  for guard_key in ro.serialno ro.boot.serialno ro.vendor.oem.serialno persist.sys.ipriv.serial; do
    [ "$(getprop "$guard_key")" = "$FAKE_SERIAL" ] || return 1
  done
  [ "$(getprop persist.sys.ipriv.fid)" = "$FAKE_FID" ]
}

guard_report_ok() {
  guard_pid=$1; guard_uid=$2; guard_start=$3
  guard_user=$((guard_uid / 100000))
  guard_directory="/data/user_de/$guard_user/$GUARD_PACKAGE/files/imei_privacy_health"
  guard_report="$guard_directory/$guard_pid.state"
  guard_read_report "$guard_report" "$guard_directory" || { GUARD_REASON=NO_PROCESS_REPORT; return 1; }
  case "$guard_record" in ''|*[!a-zA-Z0-9_\ ]*) GUARD_REASON=INVALID_PROCESS_REPORT; return 1;; esac
  # read, not eval/source; token count and every field are checked.
  set -- $guard_record
  [ "$#" -eq 7 ] && [ "$1" = v1 ] && [ "$2" = "$GUARD_NONCE" ] &&
    [ "$3" = "$guard_pid" ] && [ "$4" = "$guard_start" ] || { GUARD_REASON=STALE_PROCESS_REPORT; return 1; }
  guard_number "$5" || { GUARD_REASON=INVALID_HEALTH_TIME; return 1; }
  guard_report_time=$5; guard_report_state=$6; guard_report_reason=$7
  case "$guard_report_reason" in ''|*[!A-Z0-9_]*) GUARD_REASON=INVALID_HEALTH_REASON; return 1;; esac
  guard_clock_now=$(guard_now)
  [ "$guard_report_time" -le "$guard_clock_now" ] &&
    [ $((guard_clock_now - guard_report_time)) -le "$GUARD_TTL" ] || { GUARD_REASON=HEALTH_LEASE_EXPIRED; return 1; }
  [ "$guard_report_state" = PASS ] || { GUARD_REASON="SELFTEST_$guard_report_reason"; return 1; }
}

guard_read_report() {
  [ -f "$1" ] && [ ! -L "$1" ] && [ ! -L "$2" ] || return 1
  [ "$(readlink -f "$2")" = "$2" ] || return 1
  [ "$(stat -c %u "$1" 2>/dev/null)" = "$guard_uid" ] || return 1
  guard_record=$(head -c 256 "$1" 2>/dev/null) || return 1
}

guard_scan_processes() {
  # One awk process, instead of spawning a command for every PID.
  awk '$1 == "Uid:" {path=FILENAME; sub(".*/", "", path); path=FILENAME; sub("/status$", "", path); sub(".*/", "", path); print path, $2}' "$GUARD_PROC"/[0-9]*/status 2>/dev/null
}

guard_check_processes() {
  GUARD_PROCESS_COUNT=0; GUARD_VERIFIED_COUNT=0
  guard_processes=$(guard_scan_processes)
  guard_bad=0
  # Fixed numeric pairs from /proc; never app-supplied command lines.
  while read -r guard_pid guard_uid; do
    guard_number "$guard_pid" && guard_number "$guard_uid" || continue
    case " $GUARD_UIDS " in *" $guard_uid "*) ;; *) continue;; esac
    GUARD_PROCESS_COUNT=$((GUARD_PROCESS_COUNT + 1))
    guard_start=$(sed 's/.*) //' "$GUARD_PROC/$guard_pid/stat" 2>/dev/null | awk '{print $20}')
    if guard_ticks "$guard_start" && guard_report_ok "$guard_pid" "$guard_uid" "$guard_start"; then
      GUARD_VERIFIED_COUNT=$((GUARD_VERIFIED_COUNT + 1))
    else
      guard_bad=1
    fi
  done <<EOF
$guard_processes
EOF
  [ "$GUARD_PROCESS_COUNT" -gt 0 ] || { GUARD_REASON=NO_TARGET_PROCESS; return 1; }
  [ "$guard_bad" -eq 0 ]
}

guard_status() {
  {
    printf 'state=%s\nreason=%s\n' "$GUARD_MODE" "$GUARD_REASON"
    printf 'processes=%s\nverified=%s\n' "$GUARD_PROCESS_COUNT" "$GUARD_VERIFIED_COUNT"
    printf 'checked_uptime=%s\nchallenge=%s\n' "$(guard_now)" "$GUARD_NONCE"
  } > "$GUARD_DIR/status.tmp" && mv -f "$GUARD_DIR/status.tmp" "$GUARD_DIR/status"
}

guard_boot() {
  GUARD_READY=0
  guard_publish_property persist.sys.ipriv.guard_mode on
  guard_publish_property persist.sys.ipriv.guard_ready 0
  guard_prepare || { GUARD_REASON=FIREWALL_UNAVAILABLE; guard_discover_users; guard_safe_stop; guard_status; return 1; }
  guard_resolve_uids || { guard_safe_stop; guard_remove_stale_rules; guard_status; return 1; }
  guard_remove_stale_rules
  guard_family_setup "$GUARD_IP4" && guard_family_setup "$GUARD_IP6" || {
    GUARD_REASON=FIREWALL_ERROR; guard_gate REJECT || :; guard_safe_stop; guard_status; return 1;
  }
  guard_challenge || { GUARD_REASON=NO_CHALLENGE; guard_status; return 1; }
  printf '%s\n' "$GUARD_UIDS" > "$GUARD_DIR/uids"
  GUARD_READY=1
  guard_publish_property persist.sys.ipriv.guard_ready 1
  if [ "${FINDDEVICE_STOP_ON_FAILURE:-0}" != 1 ]; then guard_restore_target; fi
  GUARD_MODE=BLOCKED; GUARD_REASON=WAITING_FOR_SELFTEST
  guard_status
}

guard_verify_firewall() {
  for guard_tool in "$GUARD_IP4" "$GUARD_IP6"; do
    guard_first=$("$guard_tool" -w 2 -S OUTPUT 2>/dev/null | sed -n '/^-A OUTPUT /{p;q;}')
    [ "$guard_first" = "-A OUTPUT -j $GUARD_CHAIN" ] || return 1
    "$guard_tool" -w 2 -C OUTPUT -j "$GUARD_CHAIN" >/dev/null 2>&1 || return 1
    for guard_uid in $GUARD_UIDS; do
      "$guard_tool" -w 2 -C "$GUARD_CHAIN" -m owner --uid-owner "$guard_uid" -j "$GUARD_GATE" >/dev/null 2>&1 || return 1
    done
  done
}

guard_cycle() {
  read -r GUARD_NONCE < "$GUARD_DIR/nonce" || return 1
  if [ "$(getprop persist.sys.ipriv.guard_ready)" != 1 ]; then
    GUARD_MODE=BLOCKED; GUARD_REASON=GUARD_NOT_READY
    guard_gate REJECT || guard_safe_stop
    guard_status
    return 1
  fi
  guard_previous_uids=$GUARD_UIDS
  if ! guard_resolve_uids || [ "$GUARD_UIDS" != "$guard_previous_uids" ]; then
    guard_gate REJECT || :
    GUARD_READY=0; guard_publish_property persist.sys.ipriv.guard_ready 0
    GUARD_MODE=BLOCKED
    [ -n "$GUARD_UIDS" ] && GUARD_REASON=PACKAGE_UID_CHANGED
    guard_safe_stop; guard_remove_stale_rules; guard_status
    return 1
  fi
  if ! guard_verify_firewall; then
    GUARD_MODE=BLOCKED
    GUARD_REASON=FIREWALL_RULE_MISSING
    guard_gate REJECT || :
    guard_safe_stop; guard_status
    return 1
  fi
  if ! guard_root_profile_ok; then
    GUARD_MODE=BLOCKED; GUARD_REASON=ROOT_PROFILE_MISMATCH
  elif guard_check_processes; then
    GUARD_MODE=ALLOWED; GUARD_REASON=ALL_CURRENT_PROCESSES_PASSED
  else
    GUARD_MODE=BLOCKED
  fi
  if [ "$GUARD_MODE" = ALLOWED ]; then
    if ! guard_gate RETURN; then GUARD_MODE=BLOCKED; guard_safe_stop; fi
  else
    guard_gate REJECT || guard_safe_stop
    if [ "${FINDDEVICE_STOP_ON_FAILURE:-0}" = 1 ]; then
      case "$GUARD_REASON" in SELFTEST_*|ROOT_PROFILE_MISMATCH|HEALTH_LEASE_EXPIRED) guard_safe_stop;; esac
    fi
  fi
  guard_status
}

guard_cleanup() {
  # Restore only our own package changes and our own filter chains.
  guard_restore_target
  for guard_tool in "$GUARD_IP4" "$GUARD_IP6"; do
    while "$guard_tool" -w 2 -C OUTPUT -j "$GUARD_CHAIN" >/dev/null 2>&1; do
      "$guard_tool" -w 2 -D OUTPUT -j "$GUARD_CHAIN" >/dev/null 2>&1 || break
    done
    "$guard_tool" -w 2 -F "$GUARD_CHAIN" >/dev/null 2>&1 || :
    "$guard_tool" -w 2 -X "$GUARD_CHAIN" >/dev/null 2>&1 || :
    "$guard_tool" -w 2 -F "$GUARD_GATE" >/dev/null 2>&1 || :
    "$guard_tool" -w 2 -X "$GUARD_GATE" >/dev/null 2>&1 || :
  done
  guard_publish_property persist.sys.ipriv.guard_ready 0
  guard_publish_property persist.sys.ipriv.guard_mode off
  GUARD_MODE=DISABLED; GUARD_REASON=GUARD_DISABLED
  [ ! -d "$GUARD_DIR" ] || guard_status
}

guard_worker() {
  [ "$(getprop sys.ipriv.enabled)" != 0 ] || return 1
  guard_boot || return 1
  while [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] && [ ! -f "$GUARD_DIR/stopping" ]; do
    guard_cycle || return 1
    guard_now > "$GUARD_DIR/heartbeat"
    sleep 1
  done
}

guard_supervise() {
  umask 077
  mkdir -p "$GUARD_DIR" || return 1
  if ! mkdir "$GUARD_DIR/lock" 2>/dev/null; then
    if read -r guard_owner < "$GUARD_DIR/lock/pid" && guard_number "$guard_owner" && kill -0 "$guard_owner" 2>/dev/null; then return 1; fi
    rm -f "$GUARD_DIR/lock/pid"; rmdir "$GUARD_DIR/lock" 2>/dev/null || return 1
    mkdir "$GUARD_DIR/lock" || return 1
  fi
  printf '%s\n' "$$" > "$GUARD_DIR/lock/pid"
  while [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] && [ ! -f "$GUARD_DIR/stopping" ]; do
    guard_now > "$GUARD_DIR/heartbeat"
    guard_worker &
    guard_child=$!
    while kill -0 "$guard_child" 2>/dev/null; do
      sleep 2
      guard_now_value=$(guard_now)
      read -r guard_heartbeat < "$GUARD_DIR/heartbeat" || guard_heartbeat=0
      if ! guard_number "$guard_heartbeat" || [ $((guard_now_value - guard_heartbeat)) -gt 10 ] ||
         [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ -f "$GUARD_DIR/stopping" ]; then
        guard_gate REJECT || guard_safe_stop
        guard_publish_property persist.sys.ipriv.guard_ready 0
        kill "$guard_child" 2>/dev/null || :
        break
      fi
    done
    wait "$guard_child" 2>/dev/null || :
    guard_gate REJECT || guard_safe_stop
    guard_publish_property persist.sys.ipriv.guard_ready 0
    sleep 1
  done
  guard_cleanup
  rm -f "$GUARD_DIR/lock/pid"; rmdir "$GUARD_DIR/lock" 2>/dev/null || :
}

guard_stop_supervisor() {
  [ -d "$GUARD_DIR" ] || return 0
  : > "$GUARD_DIR/stopping"
  if read -r guard_owner < "$GUARD_DIR/lock/pid" 2>/dev/null && guard_number "$guard_owner"; then
    guard_stop_wait=0
    while kill -0 "$guard_owner" 2>/dev/null && [ "$guard_stop_wait" -lt 12 ]; do
      sleep 1
      guard_stop_wait=$((guard_stop_wait + 1))
    done
    kill -0 "$guard_owner" 2>/dev/null && return 1
  fi
  return 0
}

case "$0" in */guard.sh)
  MODDIR=${0%/*}
  . "$MODDIR/common.sh"
  case "${1:-}" in run) guard_supervise;; cleanup) guard_cleanup;; esac
esac
