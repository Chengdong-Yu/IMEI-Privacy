#!/usr/bin/env bash
# Isolated fixtures: never calls su, a phone, a real firewall, or package manager.
set -euo pipefail
project=$(cd "$(dirname "$0")/.." && pwd)
test_root=$(mktemp -d /tmp/ipriv-control.XXXXXXXX)
case "$(readlink -f "$test_root")" in /tmp/ipriv-control.*) ;; *) exit 1;; esac
trap 'rm -rf -- "$test_root"' EXIT
mkdir -p "$test_root/module" "$test_root/state/guard" "$test_root/bin"
cp "$project/module/control.sh" "$project/module/post-fs-data.sh" "$project/module/service.sh" "$project/module/customize.sh" "$test_root/module/"
# Keep the actual settings parser, changing only its root-owned data path.
sed "s@STATE_DIR=/data/adb/imei_privacy@STATE_DIR=$test_root/state@" "$project/module/common.sh" > "$test_root/module/common.sh"
printf 'version=1.4.5\n' > "$test_root/module/module.prop"
cat > "$test_root/module/guard.sh" <<'MOCK'
GUARD_DIR="$STATE_DIR/guard"
guard_number() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "${#1}" -le 10 ]; }
guard_now() { printf '100\n'; }
guard_cleanup() { printf 'cleaned\n' > "$STATE_DIR/cleanup.called"; }
MOCK
cat > "$test_root/bin/getprop" <<'MOCK'
#!/usr/bin/env bash
case "$1" in
sys.ipriv.enabled) printf '%s\n' "${MOCK_ACTIVE:-1}";;
sys.ipriv.boot_ready) printf '1\n';;
sys.boot_completed) printf '1\n';;
ro.build.version.sdk) printf '%s\n' "${MOCK_SDK-33}";;
persist.sys.ipriv.guard_nonce) printf '0123456789abcdef0123456789abcdef\n';;
esac
MOCK
cat > "$test_root/bin/id" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "${MOCK_UID:-0}"
MOCK
cat > "$test_root/bin/sync" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
cat > "$test_root/bin/svc" <<'MOCK'
#!/usr/bin/env bash
[[ "$*" == 'power reboot' ]] || exit 2
[[ "${MOCK_REBOOT_FAIL:-0}" != 1 ]] || exit 3
printf 'reboot\n' > "$CONTROL_TEST_ROOT/reboot.called"
MOCK
cat > "$test_root/bin/app_process" <<'MOCK'
#!/usr/bin/env bash
[[ "$1" == /system/bin && "$2" == com.richard.imeiprivacy.ScopeInspector ]] || exit 2
[[ -f "$CLASSPATH" ]] || exit 3
[[ "${MOCK_INSPECTOR_FAIL:-0}" != 1 ]] || exit 4
printf 'scope_state=MISSING\nscope_reason=CONFIG_READ\nscope_finddevice=0\nscope_phone=1\nscope_system=1\nscope_user=%s\nscope_checked_uptime=100\n' "$3"
MOCK
cat > "$test_root/module/action.sh" <<'MOCK'
#!/usr/bin/env bash
printf 'rechecked\n' > "${0%/*}/recheck.called"
MOCK
chmod +x "$test_root/bin/"*
export PATH="$test_root/bin:$PATH"
export CONTROL_TEST_ROOT="$test_root"
control() { bash "$test_root/module/control.sh" "$@"; }
count=0
check() { ((count+=1)); }

out=$(control status)
[[ "$out" == *pending_enabled=1* && "$out" == *active_enabled=1* && "$out" == *guard_state=UNKNOWN* ]]
[[ ! -e "$test_root/state/ui.conf" ]]; check

out=$(control set-enabled 0)
[[ "$out" == *pending_enabled=0* && "$out" == *active_enabled=1* ]]
[[ "$(cat "$test_root/state/ui.conf")" == IMEI_PRIVACY_ENABLED=0 ]]
# Git Bash on NTFS does not expose POSIX chmod semantics; Android/Linux does.
case "$(uname -s)" in MINGW*|MSYS*) ;; *) [[ "$(stat -c %a "$test_root/state/ui.conf")" == 600 ]];; esac
check

out=$(control set-enabled 1)
[[ "$out" == *pending_enabled=1* ]]; check

if control set-enabled '0;touch PWNED' >/dev/null; then exit 1; fi
[[ "$(cat "$test_root/state/ui.conf")" == IMEI_PRIVACY_ENABLED=1 ]]; check

printf 'IMEI_PRIVACY_ENABLED=0;touch PWNED\n' > "$test_root/state/ui.conf"
out=$(control status)
[[ "$out" == *pending_enabled=1* && ! -e PWNED ]]; check

touch "$test_root/module/disable"
out=$(control status); [[ "$out" == *module_disabled=1* ]]
if control set-enabled 0 >/dev/null; then exit 1; fi
if control recheck >/dev/null; then exit 1; fi
rm "$test_root/module/disable"; check

MOCK_ACTIVE=0; export MOCK_ACTIVE
if control recheck >/dev/null; then exit 1; fi
[[ ! -e "$test_root/module/recheck.called" ]]; check

MOCK_ACTIVE=1
control recheck >/dev/null
[[ -f "$test_root/module/recheck.called" ]]; check

MOCK_UID=2000; export MOCK_UID
if control set-enabled 0 >/dev/null; then exit 1; fi
MOCK_UID=0; check

if control arbitrary-command >/dev/null; then exit 1; fi
check

printf 'state=ALLOWED\nreason=ALL_CURRENT_PROCESSES_PASSED\nprocesses=2\nverified=2\nchecked_uptime=100\nchallenge=0123456789abcdef0123456789abcdef\n' > "$test_root/state/guard/status"
out=$(control status)
[[ "$out" == *guard_state=ALLOWED* && "$out" == *nonce_match=1* && "$out" != *challenge=* ]]
[[ "$out" != *0123456789abcdef* ]]; check

control set-enabled 0 >/dev/null
MOCK_ACTIVE=0
bash "$test_root/module/post-fs-data.sh"
[[ -f "$test_root/state/cleanup.called" && ! -f "$test_root/state/generated.conf" ]]
bash "$test_root/module/service.sh"
[[ ! -f "$test_root/state/generated.conf" ]]; check

# No implicit reboot: ordinary refresh/setting/recheck paths never call svc.
[[ ! -e "$test_root/reboot.called" ]]; check
if control reboot >/dev/null; then exit 1; fi
[[ ! -e "$test_root/reboot.called" ]]; check
out=$(control reboot-confirmed)
[[ "$out" == *reboot_requested=1* && -f "$test_root/reboot.called" ]]; check
MOCK_REBOOT_FAIL=1; export MOCK_REBOOT_FAIL
if control reboot-confirmed >/dev/null; then exit 1; fi
MOCK_REBOOT_FAIL=0; check
MOCK_UID=2000
if control reboot-confirmed >/dev/null; then exit 1; fi
MOCK_UID=0; check

# Missing/failed inspectors yield UNKNOWN, never falsely claim missing scope.
out=$(control status)
[[ "$out" == *scope_state=UNKNOWN* && "$out" == *scope_reason=INSPECTOR_UNAVAILABLE* ]]; check
mkdir -p "$test_root/module/system/app/ImeiPrivacy"
touch "$test_root/module/system/app/ImeiPrivacy/ImeiPrivacy.apk"
out=$(control status 10)
[[ "$out" == *scope_state=MISSING* && "$out" == *scope_user=10* && "$out" == *scope_finddevice=0* ]]; check
MOCK_INSPECTOR_FAIL=1; export MOCK_INSPECTOR_FAIL
out=$(control status)
[[ "$out" == *scope_state=UNKNOWN* ]]; check
MOCK_INSPECTOR_FAIL=0
if control status '0;reboot' >/dev/null; then exit 1; fi
check

# Installer refuses unsupported/unknown systems before package/mount setup.
install_fixture() {
  bash -c 'ui_print() { :; }; abort() { exit 1; }; set_perm() { :; }; set_perm_recursive() { :; }; MODPATH=${1%/*}; . "$1"' fixture "$test_root/module/customize.sh"
}
MOCK_SDK=28; export MOCK_SDK
if install_fixture; then exit 1; fi
check
MOCK_SDK=32
if install_fixture; then exit 1; fi
check
MOCK_SDK=33
install_fixture; check
MOCK_SDK=37
install_fixture; check
MOCK_SDK=invalid
if install_fixture; then exit 1; fi
check

printf 'UI control mock scenarios passed: %s\n' "$count"
