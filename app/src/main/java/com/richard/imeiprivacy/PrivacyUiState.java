package com.richard.imeiprivacy;

/** Pure presentation mapping, independent of Compose and root execution. */
final class PrivacyUiState {
    enum Tone { PRIMARY, NEUTRAL, WARNING, ERROR }

    final String statusTitle, statusSummary, scopeTitle, scopeSummary;
    final String rootValue, moduleVersion, verifiedValue, reason, reasonCode;
    final String currentValue, pendingValue, settingSummary, refreshLabel, snapshotLabel;
    final Tone statusTone, scopeTone;
    final boolean pendingEnabled, switchEnabled, refreshEnabled, recheckEnabled, rebootEnabled;
    final boolean needsReboot, busy, scopeNeedsAction;

    PrivacyUiState(ModuleStatus status, boolean busy, boolean authorized, boolean denied,
            boolean legacyInstalled, String error, long ageSeconds, boolean autoRefresh) {
        this.busy = busy;
        pendingEnabled = status != null && status.pendingEnabled;
        switchEnabled = !busy && authorized && !denied && status != null && status.available && !status.moduleDisabled;
        refreshEnabled = !busy && !denied;
        recheckEnabled = !busy && authorized && !denied && status != null && status.canRecheck();
        rebootEnabled = !busy && !denied;
        needsReboot = status != null && status.needsReboot();
        refreshLabel = busy ? "正在读取…" : denied ? "root 未授权"
                : authorized ? "刷新状态" : "授权并读取状态";
        rootValue = denied ? "未授权或不可用" : authorized ? "已授权" : "尚未请求";
        moduleVersion = status == null ? "未读取" : status.moduleVersion;
        currentValue = status == null || status.activeEnabled == null ? "未知" : status.activeEnabled ? "开启" : "关闭";
        pendingValue = status == null ? "未读取" : status.pendingEnabled ? "开启" : "关闭";
        settingSummary = status == null ? "授权读取后可修改设置"
                : needsReboot ? "设置已保存，完整重启后生效。当前保护尚未改变。" : "已保存设置与本次启动一致。";
        verifiedValue = status != null && status.processes >= 0 && status.verified >= 0
                ? status.verified + " / " + status.processes : "未验证";
        reasonCode = status == null ? "NOT_CHECKED" : status.reason;
        reason = reasonDescription(reasonCode);
        snapshotLabel = status == null ? "不读取、不展示真实 IMEI"
                : (busy ? "正在刷新" : autoRefresh ? "仅前台自动刷新" : "自动刷新已暂停")
                + " · " + Math.max(0, ageSeconds) + " 秒前读取";

        String title, body;
        Tone tone = Tone.WARNING;
        if (status == null) {
            title = error == null ? "等待读取状态" : "暂时无法确认状态";
            body = errorDescription(error);
        } else switch (status.state) {
            case "ALLOWED":
                title = "自检通过，允许联网";
                body = "当前采样进程的已覆盖路径均通过验证。仍存在多进程检测间隔，不代表所有信息绝不外泄。";
                tone = Tone.PRIMARY;
                break;
            case "BLOCKED":
                title = "查找设备已断网";
                body = "尚未满足放行条件，守护保持阻断。等待自检、没有进程或报告缺失，都不会当作已经通过。";
                tone = Tone.NEUTRAL;
                break;
            case "STOPPED":
                title = "查找设备已停用";
                body = "守护已确认停用目标应用，可能因防火墙不可用、共享 UID 或额外停用策略。会影响查找设备功能。";
                break;
            case "DISABLED":
                title = "IMEI 伪装已关闭";
                body = "本次启动配置已关闭 Hook、属性覆盖和关联守护。不主动读取或恢复真实 IMEI，系统值由固件提供。";
                tone = Tone.NEUTRAL;
                break;
            case "ERROR":
                title = "保护异常，需要处理";
                body = "无法确认完整断网或停用保护。请检查 root、防火墙与 LSPosed，不要把此状态理解为已安全隔离。";
                tone = Tone.ERROR;
                break;
            default:
                title = status.moduleDisabled ? "root 模块已停用" : "状态尚未验证";
                body = !status.bootReady ? "尚未读取到模块启动状态。请安装完整 ZIP、确认 LSPosed 作用域，并完整重启。"
                        : "状态过期、守护不可确认或报告不完整。旧的放行记录不能作为有效保护，请刷新或检查日志。";
        }
        statusTitle = title;
        statusSummary = body;
        statusTone = tone;

        ScopeStatus scope = status == null ? ScopeStatus.unknown("NOT_CHECKED") : status.scope;
        scopeNeedsAction = scope.requiresAction();
        Tone scopeColor = Tone.WARNING;
        switch (scope.state) {
            case "READY":
                title = "关键作用域已勾选";
                body = "新模块已启用，查找设备、电话服务和系统框架已配置。这里只确认保存的配置，运行效果仍以自检为准。";
                scopeColor = Tone.PRIMARY;
                break;
            case "MODULE_NOT_FOUND":
                title = "请启用新包名的模块";
                body = "请前往 LSPosed 启用 com.richard.imeiprivacy，勾选关键作用域，再完整重启。旧包名的启用状态不会自动继承。";
                break;
            case "MODULE_DISABLED":
                title = "LSPosed 模块未开启";
                body = "请在 LSPosed 开启 IMEI Privacy，并勾选查找设备、电话服务和系统框架。更改后需要完整重启。";
                break;
            case "MISSING":
                title = "作用域未勾选完整";
                body = "缺少：" + scope.missingLabels() + "。请前往 LSPosed 勾选后完整重启，应用内开关不会代为勾选。";
                break;
            default:
                title = "作用域尚未确认";
                body = "请在 LSPosed 启用模块并勾选查找设备、电话服务和系统框架。"
                        + (status == null ? "授权读取后尝试检查配置。" : "当前无法确认（" + scope.reason
                        + "），不代表一定未勾选。隐藏管理器可从常驻通知或快捷方式进入。");
        }
        scopeTitle = title;
        scopeSummary = body + (legacyInstalled ? "\n旧包 com.dsh.imeiprivacy 仍安装，请先停用旧模块，避免重复 Hook。" : "");
        scopeTone = scopeColor;
    }

    private static String errorDescription(String error) {
        if (error == null) return "先授权读取运行状态。尚未读取，不代表保护已经生效。";
        switch (error) {
            case "ROOT_DENIED": case "ROOT_UNAVAILABLE":
                return "root 未授权或不可用，本次应用进程会话不会再次执行 su。请检查 root 管理器，结束应用进程后重新打开。";
            case "ROOT_TIMEOUT":
                return "root 操作超时，未确认结果。首次授权超时后本次会话停止申请，请检查 root 管理器后重新打开。";
            case "MODULE_MISSING":
                return "未找到配套控制接口，请安装完整 1.4.7 ZIP 并重启。仅更新 APK 不能替代 root 模块。";
            case "STATUS_INVALID":
                return "无法验证状态格式，请确认 APK 与 root 模块配套。未经验证的结果不会显示为已通过。";
            default:
                return "操作未得到成功确认，自动刷新已暂停。请检查完整模块、root 授权及停用状态，再手动刷新。";
        }
    }

    private static String reasonDescription(String reason) {
        switch (reason) {
            case "ALL_CURRENT_PROCESSES_PASSED": return "当前采样进程全部通过";
            case "NO_TARGET_PROCESS": return "暂无目标进程，可手动打开查找设备相关设置";
            case "WAITING_FOR_SELFTEST": return "等待自检";
            case "NO_PROCESS_REPORT": return "缺少进程报告，请确认 LSPosed 作用域";
            case "HEALTH_LEASE_EXPIRED": return "报告已过期";
            case "ROOT_PROFILE_MISMATCH": return "root 属性覆盖不一致";
            case "SHARED_UID_UNSAFE": return "目标共享 UID，已避免封禁其他应用";
            case "GUARD_DISABLED": return "关联守护已关闭";
            case "FIREWALL_UNAVAILABLE": case "FIREWALL_ERROR": case "FIREWALL_RULE_MISSING": return "防火墙或规则异常";
            case "ENFORCEMENT_UNAVAILABLE": return "断网和停用均无法确认";
            default: return reason.startsWith("SELFTEST_") ? "进程自检未通过或仍在等待" : "请结合状态与 LSPosed 日志检查";
        }
    }
}
