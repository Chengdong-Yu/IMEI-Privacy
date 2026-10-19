#!/system/bin/sh

imei_api=$(getprop ro.build.version.sdk)
case "$imei_api" in ''|*[!0-9]*) abort "无法确认 Android API，停止安装。";; esac
[ "$imei_api" -ge 33 ] || abort "IMEI Privacy 需要 Android 13（API 33）或更高版本。"

ui_print "- 安装 IMEI Privacy Shield"
ui_print "- 属性覆盖将在本次重启后生效"
ui_print "- 首次启动时为双卡生成随机且校验合法的伪 IMEI"
ui_print "- 首次启动后请在 LSPosed 中启用 IMEI Privacy"
ui_print "- 新包名 com.richard.imeiprivacy：请重新启用新模块，停用旧包模块"
ui_print "- 请在 LSPosed 中使用模块提供的全部推荐作用域"
ui_print "- 查找设备默认断网，仅当前全部进程自检通过后放行 IPv4/IPv6"
ui_print "- 自检过期/失败会重新阻断；防火墙不可用时会尝试禁用查找设备"
ui_print "- 操作按钮可复检；请不要只安装 APK，必须更新整个模块后重启"
ui_print "- IMEI Privacy 桌面/LSPosed 设置页可查看状态；总开关下次完整重启生效"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755
set_perm "$MODPATH/guard.sh" 0 0 0755
set_perm "$MODPATH/control.sh" 0 0 0755
