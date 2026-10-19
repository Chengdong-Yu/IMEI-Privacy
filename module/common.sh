#!/system/bin/sh

MODDIR=${0%/*}
CONFIG="$MODDIR/config.conf"
STATE_DIR=/data/adb/imei_privacy
STATE_CONFIG="$STATE_DIR/generated.conf"
UI_SETTINGS="$STATE_DIR/ui.conf"

IMEI_SLOT_0=358240051234565
IMEI_SLOT_1=358240056543218
MEID_SLOT_0=A1000000000001
MEID_SLOT_1=A1000000000002
ANDROID_ID=a1b2c3d4e5f67890
OAID=4f6a55d2-7c8e-4a91-b2d3-6e7f8091a2b3
GAID=7b8c69e3-8d9f-4ba2-c3e4-7f8091a2b3c4
ONETRACK_ID=91a2b3c4-d5e6-47f8-9012-3456789abcde
IMSI_SLOT_0=001010123456789
IMSI_SLOT_1=001010987654321
ICCID_SLOT_0=8986001234567890123
ICCID_SLOT_1=8986009876543210987
FAKE_SERIAL=900154359217
FAKE_MAC=02:12:34:56:78:9A
FAKE_FID=2a3b4c5d6e7f8091a2b3c4d5e6f70819
FAKE_PUSH_TOKEN=privacy000000000000000000000000000000000000000000000000000000000
FINDDEVICE_STOP_ON_FAILURE=0

load_privacy_config() {
  if [ -r "$CONFIG" ]; then
    . "$CONFIG"
  fi
  if [ -r "$STATE_CONFIG" ]; then
    . "$STATE_CONFIG"
  fi
}

random_six_digits() {
  digits=""
  while [ "${#digits}" -lt 6 ]; do
    chunk=$(od -An -N16 -tu1 /dev/urandom 2>/dev/null | tr -cd '0-9')
    digits="${digits}${chunk}"
  done
  printf '%.6s' "$digits"
}

random_digits() {
  wanted=$1
  digits=""
  while [ "${#digits}" -lt "$wanted" ]; do
    chunk=$(od -An -N32 -tu1 /dev/urandom 2>/dev/null | tr -cd '0-9')
    digits="${digits}${chunk}"
  done
  printf "%.*s" "$wanted" "$digits"
}

random_hex() {
  wanted=$1
  hex=""
  while [ "${#hex}" -lt "$wanted" ]; do
    chunk=$(od -An -N32 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')
    hex="${hex}${chunk}"
  done
  printf "%.*s" "$wanted" "$hex"
}

random_uuid() {
  hex=$(random_hex 32)
  printf '%s-%s-%s-%s-%s' \
    "$(printf '%.8s' "$hex")" \
    "$(printf '%s' "$hex" | cut -c9-12)" \
    "4$(printf '%s' "$hex" | cut -c14-16)" \
    "a$(printf '%s' "$hex" | cut -c18-20)" \
    "$(printf '%s' "$hex" | cut -c21-32)"
}

append_imei_check_digit() {
  body=$1
  remaining=$body
  position=1
  sum=0
  while [ -n "$remaining" ]; do
    digit=${remaining%"${remaining#?}"}
    remaining=${remaining#?}
    if [ $((position % 2)) -eq 0 ]; then
      digit=$((digit * 2))
      [ "$digit" -gt 9 ] && digit=$((digit - 9))
    fi
    sum=$((sum + digit))
    position=$((position + 1))
  done
  printf '%s%s' "$body" "$(((10 - (sum % 10)) % 10))"
}

ensure_privacy_config() {
  if [ -s "$STATE_CONFIG" ] && grep -q '^PROFILE_VERSION=2$' "$STATE_CONFIG"; then
    return 0
  fi

  mkdir -p "$STATE_DIR" || return 1
  first=$(append_imei_check_digit "35824005$(random_six_digits)")
  second=$(append_imei_check_digit "35824005$(random_six_digits)")
  while [ "$first" = "$second" ]; do
    second=$(append_imei_check_digit "35824005$(random_six_digits)")
  done

  mac_tail=$(random_hex 10)
  fake_mac=$(printf '02:%s:%s:%s:%s:%s' \
    "$(printf '%s' "$mac_tail" | cut -c1-2)" \
    "$(printf '%s' "$mac_tail" | cut -c3-4)" \
    "$(printf '%s' "$mac_tail" | cut -c5-6)" \
    "$(printf '%s' "$mac_tail" | cut -c7-8)" \
    "$(printf '%s' "$mac_tail" | cut -c9-10)")

  temp="$STATE_CONFIG.tmp.$$"
  {
    echo "PROFILE_VERSION=2"
    echo "IMEI_SLOT_0=$first"
    echo "IMEI_SLOT_1=$second"
    echo "MEID_SLOT_0=A1$(random_hex 12)"
    echo "MEID_SLOT_1=A1$(random_hex 12)"
    echo "ANDROID_ID=$(random_hex 16)"
    echo "OAID=$(random_uuid)"
    echo "GAID=$(random_uuid)"
    echo "ONETRACK_ID=$(random_uuid)"
    echo "IMSI_SLOT_0=00101$(random_digits 10)"
    echo "IMSI_SLOT_1=00101$(random_digits 10)"
    echo "ICCID_SLOT_0=898600$(random_digits 13)"
    echo "ICCID_SLOT_1=898600$(random_digits 13)"
    echo "FAKE_SERIAL=$(random_digits 12)"
    echo "FAKE_MAC=$fake_mac"
    echo "FAKE_FID=$(random_hex 32)"
    echo "FAKE_PUSH_TOKEN=privacy$(random_hex 56)"
  } > "$temp" || return 1
  chmod 0600 "$temp"
  mv -f "$temp" "$STATE_CONFIG"
}

load_privacy_config

load_ui_settings() {
  IMEI_PRIVACY_ENABLED=1
  if [ -f "$UI_SETTINGS" ] && [ ! -L "$UI_SETTINGS" ]; then
    read -r ui_setting < "$UI_SETTINGS" || ui_setting=""
    # Never source settings written through the UI. Corrupt/missing means on.
    [ "$ui_setting" != IMEI_PRIVACY_ENABLED=0 ] || IMEI_PRIVACY_ENABLED=0
  fi
}

load_ui_settings

find_resetprop() {
  if command -v resetprop >/dev/null 2>&1; then
    command -v resetprop
  elif [ -x /data/adb/ksu/bin/resetprop ]; then
    echo /data/adb/ksu/bin/resetprop
  elif [ -x /data/adb/magisk/resetprop ]; then
    echo /data/adb/magisk/resetprop
  fi
}

RESETPROP=$(find_resetprop)

set_masked_prop() {
  key=$1
  value=$2
  current=$(getprop "$key")
  [ "$current" = "$value" ] && return 0

  if [ -n "$RESETPROP" ]; then
    "$RESETPROP" -n "$key" "$value" >/dev/null 2>&1
  else
    setprop "$key" "$value" >/dev/null 2>&1
  fi
}

apply_imei_mask() {
  # Never use radio properties as the Java hook's profile source: they can still
  # contain hardware values while init/qcril or resetprop are starting.
  set_masked_prop persist.sys.ipriv.imei0 "$IMEI_SLOT_0"
  set_masked_prop persist.sys.ipriv.imei1 "$IMEI_SLOT_1"
  set_masked_prop persist.sys.ipriv.meid0 "$MEID_SLOT_0"
  set_masked_prop persist.sys.ipriv.meid1 "$MEID_SLOT_1"
  set_masked_prop ro.vendor.oem.imei "$IMEI_SLOT_0"
  set_masked_prop ro.vendor.oem.imei1 "$IMEI_SLOT_0"
  set_masked_prop ro.vendor.oem.imei2 "$IMEI_SLOT_1"

  set_masked_prop persist.vendor.radio.imei "$IMEI_SLOT_0"
  set_masked_prop persist.vendor.radio.imei1 "$IMEI_SLOT_0"
  set_masked_prop persist.vendor.radio.imei2 "$IMEI_SLOT_1"

  set_masked_prop ro.ril.oem.imei "$IMEI_SLOT_0"
  set_masked_prop ro.ril.oem.imei1 "$IMEI_SLOT_0"
  set_masked_prop ro.ril.oem.imei2 "$IMEI_SLOT_1"

  set_masked_prop persist.radio.imei "$IMEI_SLOT_0"
  set_masked_prop persist.radio.imei1 "$IMEI_SLOT_0"
  set_masked_prop persist.radio.imei2 "$IMEI_SLOT_1"

  set_masked_prop ro.ril.miui.imei0 "$IMEI_SLOT_0"
  set_masked_prop ro.ril.miui.imei1 "$IMEI_SLOT_1"

  set_masked_prop ro.serialno "$FAKE_SERIAL"
  set_masked_prop ro.boot.serialno "$FAKE_SERIAL"
  set_masked_prop ro.vendor.oem.serialno "$FAKE_SERIAL"

  set_masked_prop persist.sys.ipriv.androidid "$ANDROID_ID"
  set_masked_prop persist.sys.ipriv.oaid "$OAID"
  set_masked_prop persist.sys.ipriv.gaid "$GAID"
  set_masked_prop persist.sys.ipriv.instance "$ONETRACK_ID"
  set_masked_prop persist.sys.ipriv.imsi0 "$IMSI_SLOT_0"
  set_masked_prop persist.sys.ipriv.imsi1 "$IMSI_SLOT_1"
  set_masked_prop persist.sys.ipriv.iccid0 "$ICCID_SLOT_0"
  set_masked_prop persist.sys.ipriv.iccid1 "$ICCID_SLOT_1"
  set_masked_prop persist.sys.ipriv.serial "$FAKE_SERIAL"
  set_masked_prop persist.sys.ipriv.mac "$FAKE_MAC"
  set_masked_prop persist.sys.ipriv.fid "$FAKE_FID"
  set_masked_prop persist.sys.ipriv.push "$FAKE_PUSH_TOKEN"
}

watch_masked_prop() {
  watched_key=$1
  watched_value=$2

  while true; do
    set_masked_prop "$watched_key" "$watched_value"
    if [ -n "$RESETPROP" ]; then
      # Block without polling while the value is correct, and wake as soon as
      # qcril or init changes it.
      "$RESETPROP" -w "$watched_key" "$watched_value" >/dev/null 2>&1
    else
      sleep 2
    fi
  done
}

start_imei_watchers() {
  watch_masked_prop ro.vendor.oem.imei "$IMEI_SLOT_0" &
  watch_masked_prop ro.vendor.oem.imei1 "$IMEI_SLOT_0" &
  watch_masked_prop ro.vendor.oem.imei2 "$IMEI_SLOT_1" &
  watch_masked_prop persist.vendor.radio.imei "$IMEI_SLOT_0" &
  watch_masked_prop persist.vendor.radio.imei1 "$IMEI_SLOT_0" &
  watch_masked_prop persist.vendor.radio.imei2 "$IMEI_SLOT_1" &
  watch_masked_prop ro.ril.oem.imei "$IMEI_SLOT_0" &
  watch_masked_prop ro.ril.oem.imei1 "$IMEI_SLOT_0" &
  watch_masked_prop ro.ril.oem.imei2 "$IMEI_SLOT_1" &
  watch_masked_prop persist.radio.imei "$IMEI_SLOT_0" &
  watch_masked_prop persist.radio.imei1 "$IMEI_SLOT_0" &
  watch_masked_prop persist.radio.imei2 "$IMEI_SLOT_1" &
  watch_masked_prop ro.ril.miui.imei0 "$IMEI_SLOT_0" &
  watch_masked_prop ro.ril.miui.imei1 "$IMEI_SLOT_1" &
  watch_masked_prop ro.serialno "$FAKE_SERIAL" &
  watch_masked_prop ro.boot.serialno "$FAKE_SERIAL" &
  watch_masked_prop ro.vendor.oem.serialno "$FAKE_SERIAL" &
  wait
}
