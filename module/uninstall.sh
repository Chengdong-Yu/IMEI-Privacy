#!/system/bin/sh

# 模块不会改 modem NV 或系统分区。移除后，qcril 会在下次完整重启时恢复真实属性。
. "${0%/*}/common.sh"
. "${0%/*}/guard.sh"
if ! guard_stop_supervisor; then
  guard_cleanup
  echo "隐私守护正在退出，保留停止标记直到重启，避免守护重新添加规则。"
  exit 0
fi
guard_cleanup
for saved_setting in /data/adb/imei_privacy/guard/disabled.[0-9]*; do
  if [ -f "$saved_setting" ]; then
    echo "查找设备启用状态尚未恢复；已保留 guard/disabled.USER 原状态，请系统启动后手动恢复。"
    exit 0
  fi
done
rm -rf /data/adb/imei_privacy
exit 0
