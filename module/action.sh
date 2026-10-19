#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"
. "$MODDIR/guard.sh"

if [ "${1:-recheck}" != status ]; then
  if [ "$(getprop sys.ipriv.enabled)" = 0 ]; then
    echo "隐私保护当前已关闭。请在 IMEI Privacy 界面开启并完整重启后复检。"
    exit 1
  fi
  ensure_privacy_config
  load_privacy_config
  apply_imei_mask
  guard_prepare || { guard_discover_users; guard_safe_stop; guard_status; echo "防火墙不可用，已尝试停用查找设备；请检查状态并重启。"; sed '/^challenge=/d' "$GUARD_DIR/status" 2>/dev/null; exit 1; }
  guard_gate REJECT || guard_safe_stop
  if guard_challenge; then
    echo "已先阻断查找设备联网，正在请求复检……"
    if [ "$(getprop persist.sys.ipriv.guard_ready)" = 1 ]; then guard_restore_target; fi
    guard_wait=0
    while [ "$guard_wait" -lt 8 ]; do
      sleep 1
      guard_wait=$((guard_wait + 1))
      if [ -r "$GUARD_DIR/status" ] && grep -q "^challenge=$GUARD_NONCE$" "$GUARD_DIR/status"; then
        if grep -q '^state=ALLOWED$\|^reason=SELFTEST_' "$GUARD_DIR/status"; then break; fi
      fi
    done
  else
    guard_publish_property persist.sys.ipriv.guard_ready 0
    GUARD_MODE=BLOCKED; GUARD_REASON=RECHECK_CHALLENGE_FAILED
    guard_status
  fi
fi
echo "查找设备隐私保护状态（不显示任何个人标识）："
guard_status_fresh=0
if [ -r "$GUARD_DIR/status" ]; then
  guard_checked=$(sed -n 's/^checked_uptime=//p' "$GUARD_DIR/status")
  guard_status_nonce=$(sed -n 's/^challenge=//p' "$GUARD_DIR/status")
  guard_live_nonce=$(getprop persist.sys.ipriv.guard_nonce)
  guard_action_now=$(guard_now)
  if guard_number "$guard_checked" && [ "$guard_checked" -le "$guard_action_now" ] &&
     [ $((guard_action_now - guard_checked)) -le 5 ] && [ "$guard_status_nonce" = "$guard_live_nonce" ]; then
    guard_status_fresh=1
  fi
fi
if [ "$guard_status_fresh" = 1 ]; then sed '/^challenge=/d' "$GUARD_DIR/status"; else echo "state=UNKNOWN"; echo "reason=SUPERVISOR_NOT_READY"; fi
echo "ALLOWED=已通过；BLOCKED=已断网；STOPPED=已停用；ERROR/UNKNOWN=无法确认拦截，需人工处理。"
echo "无进程时请手动打开查找设备相关设置；模块不会触发定位或绑定。"
echo "详细结果：LSPosed 日志 ImeiPrivacy/Health。"
