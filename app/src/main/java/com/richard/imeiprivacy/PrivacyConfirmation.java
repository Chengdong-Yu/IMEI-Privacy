package com.richard.imeiprivacy;

/** Root commands are reachable only after the corresponding confirmation. */
enum PrivacyConfirmation {
    ENABLE("开启 IMEI 伪装？", "保存后需完整重启。IMEI / MEID Hook、root 属性覆盖和查找设备保护会一起启用，当前状态不会立即改变。", "保存设置", "set-enabled 1", false),
    DISABLE("关闭 IMEI 伪装？", "下次完整重启后会停止标识伪装和关联保护，应用可能重新获得真实标识。当前保护保持不变，不会读取或保存真实 IMEI。", "确认关闭", "set-enabled 0", true),
    RECHECK("重新验证保护状态？", "会先阻断查找设备联网，再检查已覆盖的伪装路径。只有当前进程全部通过才放行，不会触发定位或绑定。", "开始自检", "recheck", false),
    REBOOT("确认重启设备？", "请先保存其他应用中的工作。设备将执行完整重启并应用已保存设置，但重启不能代替在 LSPosed 启用模块和勾选作用域。", "重启设备", "reboot-confirmed", true),
    SCOPE("请开启模块与作用域", "", "前往 LSPosed", null, false),
    MANAGER_HELP("打开 LSPosed 管理器", "未找到可直接打开的管理器。若使用隐藏/寄生管理器，请点击 LSPosed 常驻通知或管理器快捷方式，启用 com.richard.imeiprivacy 并勾选关键作用域。", "知道了", null, false);

    final String title, summary, confirmLabel, command;
    final boolean caution;

    PrivacyConfirmation(String title, String summary, String confirmLabel, String command, boolean caution) {
        this.title = title;
        this.summary = summary;
        this.confirmLabel = confirmLabel;
        this.command = command;
        this.caution = caution;
    }
}
