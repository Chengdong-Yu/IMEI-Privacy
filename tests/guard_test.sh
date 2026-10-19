#!/usr/bin/env bash
# Runs only mocks, never a real firewall, package manager or phone command.
set -euo pipefail
test_root=$(mktemp -d /tmp/ipriv-guard.XXXXXXXX)
case "$(readlink -f "$test_root")" in /tmp/ipriv-guard.*) ;; *) exit 1;; esac
trap 'rm -rf -- "$test_root"' EXIT
source "$(dirname "$0")/../module/guard.sh"
GUARD_DIR="$test_root/state"
GUARD_PACKAGES="$test_root/packages.list"
GUARD_USERS="$test_root/users"
GUARD_PROC="$test_root/proc"
MODDIR="$test_root/module"
mkdir -p "$GUARD_DIR" "$GUARD_USERS" "$GUARD_PROC" "$MODDIR"
touch "$GUARD_USERS/0.xml"
printf 'com.xiaomi.finddevice 6110 ignored\ncom.xiaomi.account 10001 ignored\n' > "$GUARD_PACKAGES"
GUARD_NONCE=0123456789abcdef0123456789abcdef
printf '%s\n' "$GUARD_NONCE" > "$GUARD_DIR/nonce"
printf '100.00 0.00\n' > "$GUARD_PROC/uptime"
declare -A gates=() jumps=() owners=() reports=()
profile_ok=1
fail6=0
setting=0
pm_failed=0
pm_no_effect=0
force_stops=0
test_count=0
calls=""
guard_publish_property() { :; }
getprop() { printf '1\n'; }
guard_root_profile_ok() { [[ "$profile_ok" == 1 ]]; }
guard_current_setting() { printf '%s\n' "$setting"; }
guard_prepare() { GUARD_IP4=mock4; GUARD_IP6=mock6; }
timeout() { shift; "$@"; }
pm() {
  [[ "$pm_failed" == 0 ]] || return 1
  calls+="pm:$*;"
  [[ "${*: -1}" == com.xiaomi.finddevice ]] || { echo 'Unexpected package mutation' >&2; exit 99; }
  [[ "$pm_no_effect" != 1 ]] || return 0
  case "$1" in disable-user) setting=3;; default-state) setting=0;; enable) setting=1;; disable-until-used) setting=4;; *) return 1;; esac
}
am() { calls+="am:$*;"; ((force_stops+=1)); }
mock4() { mock_firewall 4 "$@"; }
mock6() { mock_firewall 6 "$@"; }
mock_firewall() {
  local family=$1; shift
  [[ "$family" != 6 || "$fail6" != 1 ]] || return 1
  [[ "$1" == -w && "$2" == 2 ]] || return 1
  shift 2
  local operation=$1 chain=${2:-}; shift 2
  local uid=""
  if [[ "$chain" == "$GUARD_CHAIN" && "$*" == *--uid-owner* ]]; then
    local rest="$*"; rest=${rest#*--uid-owner }; uid=${rest%% *}
  fi
  case "$operation" in
    -N) return 0;;
    -S) [[ "${jumps[$family]:-0}" == 1 ]] && printf '%s\n' "-A OUTPUT -j $GUARD_CHAIN"; return 0;;
    -C)
      if [[ "$chain" == OUTPUT ]]; then [[ "${jumps[$family]:-0}" == 1 ]];
      elif [[ "$chain" == "$GUARD_GATE" ]]; then [[ "${gates[$family]:-}" == "${*: -1}" ]];
      else [[ "${owners[$family:$uid]:-0}" == 1 ]]; fi;;
    -A|-I)
      if [[ "$chain" == OUTPUT ]]; then jumps[$family]=1;
      elif [[ "$chain" == "$GUARD_GATE" ]]; then gates[$family]=${*: -1};
      else owners[$family:$uid]=1; fi;;
    -R) gates[$family]=${*: -1};;
    -D) if [[ "$chain" == OUTPUT ]]; then jumps[$family]=0; else owners[$family:$uid]=0; fi;;
    -F) [[ "$chain" == "$GUARD_GATE" ]] && gates[$family]=""; return 0;;
    -X) return 0;;
    *) return 1;;
  esac
}
guard_read_report() {
  local pid=${1##*/}; pid=${pid%.state}
  [[ -n "${reports[$pid]:-}" ]] || return 1
  guard_record=${reports[$pid]}
}
pid_fixture() {
  local pid=$1 uid=$2 start=$3
  mkdir -p "$GUARD_PROC/$pid"
  printf 'Uid:\t%s\t%s\t%s\t%s\n' "$uid" "$uid" "$uid" "$uid" > "$GUARD_PROC/$pid/status"
  { printf '%s (fixture process) S' "$pid"; for ((field=4;field<=21;field++)); do printf ' 0'; done; printf ' %s 0 0\n' "$start"; } > "$GUARD_PROC/$pid/stat"
}
report_fixture() { reports[$1]="v1 $GUARD_NONCE $1 $2 $3 $4 $5"; }
assert_state() {
  [[ "$GUARD_MODE" == "$1" ]] || { echo "Expected state $1, got $GUARD_MODE/$GUARD_REASON" >&2; exit 1; }
  ((test_count+=1))
}
assert_closed() { [[ "${gates[4]}" == REJECT && "${gates[6]}" == REJECT ]] || { echo 'Address family left open' >&2; exit 1; }; }

GUARD_IP4=mock4
GUARD_IP6=mock6
guard_resolve_uids
guard_family_setup mock4
guard_family_setup mock6
printf '%s\n' "$GUARD_UIDS" > "$GUARD_DIR/uids"
GUARD_READY=1
pid_fixture 201 6110 111
guard_cycle
assert_state BLOCKED; assert_closed
report_fixture 201 111 100 PASS KNOWN_PATHS_VERIFIED
guard_cycle
assert_state ALLOWED
[[ "${gates[4]}" == RETURN && "${gates[6]}" == RETURN ]]

# A newly observed process invalidates the UID-wide allow immediately on scan.
pid_fixture 202 6110 222
guard_cycle
assert_state BLOCKED; assert_closed
report_fixture 202 222 100 FAIL IDENTIFIER_NOT_MASKED
guard_cycle
assert_state BLOCKED; assert_closed
report_fixture 202 222 100 PASS KNOWN_PATHS_VERIFIED
guard_cycle
assert_state ALLOWED

# PID reuse, expired leases, previous boot/recheck nonce and future timestamps.
report_fixture 202 999 100 PASS KNOWN_PATHS_VERIFIED
guard_cycle; assert_state BLOCKED; assert_closed
report_fixture 202 222 80 PASS KNOWN_PATHS_VERIFIED
guard_cycle; assert_state BLOCKED; assert_closed
reports[202]="v1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 202 222 100 PASS KNOWN_PATHS_VERIFIED"
guard_cycle; assert_state BLOCKED; assert_closed
report_fixture 202 222 101 PASS KNOWN_PATHS_VERIFIED
guard_cycle; assert_state BLOCKED; assert_closed
report_fixture 202 222 100 PASS KNOWN_PATHS_VERIFIED
profile_ok=0
guard_cycle; assert_state BLOCKED; assert_closed
profile_ok=1
guard_cycle; assert_state ALLOWED

# Never execute report contents or accept an extra token.
reports[202]='v1 $(touch BAD) 202 222 100 PASS KNOWN_PATHS_VERIFIED'
guard_cycle; assert_state BLOCKED; assert_closed
report_fixture 202 222 100 PASS KNOWN_PATHS_VERIFIED
reports[202]+=' extra'
guard_cycle; assert_state BLOCKED; assert_closed
report_fixture 202 222 100 PASS KNOWN_PATHS_VERIFIED

# IPv6 failure must revoke IPv4 allow and use package-only fallback.
fail6=1
guard_cycle || :
assert_state STOPPED
[[ "${gates[4]}" == REJECT && "$setting" == 3 && "$force_stops" -gt 0 ]]
fail6=0
guard_restore_target
[[ "$setting" == 0 && ! -e "$GUARD_DIR/disabled.0" ]]

# If even package stopping fails, report ERROR, never a misleading BLOCKED/PASS.
pm_failed=1; fail6=1
guard_cycle || :
assert_state ERROR
pm_failed=0; fail6=0
guard_restore_target

# Failure to save a recoverable prior state must not claim the package stopped.
mkdir "$GUARD_DIR/disabled.0"
guard_safe_stop 2>/dev/null
assert_state ERROR
[[ "$setting" == 0 ]]
rmdir "$GUARD_DIR/disabled.0"

# A successful PM exit code alone does not prove that restoration took effect.
guard_disable_target
pm_no_effect=1
guard_restore_target
[[ "$setting" == 3 && -f "$GUARD_DIR/disabled.0" ]]
((test_count+=1))
pm_no_effect=0
guard_restore_target
[[ "$setting" == 0 && ! -e "$GUARD_DIR/disabled.0" ]]

# Shared account UID must not retain our previous UID owner rule.
printf 'com.xiaomi.account 6110 ignored\n' >> "$GUARD_PACKAGES"
guard_cycle || :
assert_state STOPPED
[[ "${owners[4:6110]:-0}" == 0 && "${owners[6:6110]:-0}" == 0 ]]
[[ "$calls" != *'pm:disable-user --user 0 com.xiaomi.account'* ]]
guard_cleanup
[[ "${jumps[4]:-0}" == 0 && "${jumps[6]:-0}" == 0 && "$setting" == 0 ]]
((test_count+=1))
printf 'Root guard mock scenarios passed: %s\n' "$test_count"
