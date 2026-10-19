package com.richard.imeiprivacy;

import java.util.Map;

/** Saved LSPosed configuration, not proof that a live process was injected. */
final class ScopeStatus {
    final String state;
    final String reason;
    final boolean findDevice, phone, system;

    private ScopeStatus(String state, String reason, boolean findDevice, boolean phone, boolean system) {
        this.state = state;
        this.reason = reason;
        this.findDevice = findDevice;
        this.phone = phone;
        this.system = system;
    }

    static ScopeStatus configured(boolean registered, boolean enabled, boolean findDevice, boolean phone, boolean system) {
        return new ScopeStatus(!registered ? "MODULE_NOT_FOUND" : !enabled ? "MODULE_DISABLED"
                : findDevice && phone && system ? "READY" : "MISSING", "CONFIG_READ",
                findDevice, phone, system);
    }

    static ScopeStatus unknown(String reason) {
        return new ScopeStatus("UNKNOWN", reason, false, false, false);
    }

    static ScopeStatus parse(Map<String, String> values, long now, int expectedUser) {
        try {
            String time = values.get("scope_checked_uptime");
            String user = values.get("scope_user");
            if (time == null || user == null || !time.matches("[0-9]{1,10}") || !user.matches("[0-9]{1,5}"))
                return unknown("NOT_CHECKED");
            long checked = Long.parseLong(time);
            if (Integer.parseInt(user) != expectedUser || now < checked || now - checked > 10)
                return unknown("STALE_CONFIG");
            String raw = values.get("scope_state");
            String reason = values.get("scope_reason");
            if ("UNKNOWN".equals(raw)) return unknown(reason != null && reason.matches("[A-Z_]{1,48}") ? reason : "READ_FAILED");
            if (!flag(values.get("scope_finddevice")) || !flag(values.get("scope_phone")) || !flag(values.get("scope_system")))
                return unknown("INVALID_CONFIG");
            ScopeStatus result = configured(!"MODULE_NOT_FOUND".equals(raw), !"MODULE_DISABLED".equals(raw),
                    "1".equals(values.get("scope_finddevice")), "1".equals(values.get("scope_phone")), "1".equals(values.get("scope_system")));
            return result.state.equals(raw) ? result : unknown("INVALID_CONFIG");
        } catch (RuntimeException ignored) { return unknown("INVALID_CONFIG"); }
    }

    boolean requiresAction() {
        return "MODULE_NOT_FOUND".equals(state) || "MODULE_DISABLED".equals(state) || "MISSING".equals(state);
    }

    String missingLabels() {
        StringBuilder text = new StringBuilder();
        if (!findDevice) text.append("查找设备（当前用户）");
        if (!phone) { if (text.length() > 0) text.append("、"); text.append("电话服务（主用户）"); }
        if (!system) { if (text.length() > 0) text.append("、"); text.append("系统框架"); }
        return text.toString();
    }

    private static boolean flag(String value) { return "0".equals(value) || "1".equals(value); }
}
