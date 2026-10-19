package com.richard.imeiprivacy;

import java.util.HashMap;
import java.util.Map;

/** UI snapshot only. A requested setting is never treated as a health proof. */
final class ModuleStatus {
    final boolean available;
    final boolean pendingEnabled;
    final Boolean activeEnabled;
    final boolean bootReady;
    final boolean moduleDisabled;
    final String moduleVersion;
    final String state;
    final String reason;
    final int processes;
    final int verified;
    final long checkedAt;
    final ScopeStatus scope;

    private ModuleStatus(Map<String, String> values, long now, int user) {
        available = "1".equals(values.get("root")) && "1".equals(values.get("protocol"))
                && flag(values.get("pending_enabled")) && flag(values.get("module_disabled"))
                && flag(values.get("boot_ready")) && !values.containsKey("error");
        pendingEnabled = !"0".equals(values.get("pending_enabled"));
        String active = values.get("active_enabled");
        activeEnabled = "1".equals(active) ? Boolean.TRUE : "0".equals(active) ? Boolean.FALSE : null;
        bootReady = "1".equals(values.get("boot_ready"));
        moduleDisabled = "1".equals(values.get("module_disabled"));
        String version = values.get("module_version");
        moduleVersion = version != null && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ? version : "未知";
        String code = values.get("reason");
        reason = code != null && code.length() <= 96 && code.matches("[A-Z]+(?:_[A-Z]+)+") ? code : "STATUS_UNAVAILABLE";
        processes = count(values.get("processes"));
        verified = count(values.get("verified"));
        checkedAt = number(values.get("checked_uptime"));
        scope = ScopeStatus.parse(values, now, user);
        String rawState = values.get("guard_state");
        boolean fresh = checkedAt >= 0 && now >= checkedAt && now - checkedAt <= 5
                && "1".equals(values.get("nonce_match"));
        if (!available || !bootReady || moduleDisabled) state = "UNKNOWN";
        else if (Boolean.FALSE.equals(activeEnabled)) state = "DISABLED";
        else if (!Boolean.TRUE.equals(activeEnabled) || !fresh) state = "UNKNOWN";
        else if ("ALLOWED".equals(rawState)) {
            state = processes > 0 && processes == verified ? "ALLOWED" : "UNKNOWN";
        } else if ("BLOCKED".equals(rawState) || "STOPPED".equals(rawState) || "ERROR".equals(rawState)) {
            state = rawState;
        } else state = "UNKNOWN";
    }

    boolean needsReboot() {
        return available && (activeEnabled == null || !bootReady || pendingEnabled != activeEnabled);
    }

    boolean canRecheck() {
        return available && bootReady && !moduleDisabled && Boolean.TRUE.equals(activeEnabled);
    }

    static ModuleStatus parse(String text, long now) {
        return parse(text, now, 0);
    }

    static ModuleStatus parse(String text, long now, int user) {
        Map<String, String> values = new HashMap<>();
        if (text != null && text.length() <= 8192) {
            for (String line : text.split("\\r?\\n")) {
                int split = line.indexOf('=');
                if (split > 0) {
                    String key = line.substring(0, split);
                    if (values.put(key, line.substring(split + 1)) != null) {
                        // Duplicated protocol fields cannot be an unambiguous snapshot.
                        return new ModuleStatus(new HashMap<>(), now, user);
                    }
                }
            }
        }
        return new ModuleStatus(values, now, user);
    }

    private static int count(String value) {
        long number = number(value);
        return number >= 0 && number <= 10000 ? (int) number : -1;
    }

    private static boolean flag(String value) { return "0".equals(value) || "1".equals(value); }

    private static long number(String value) {
        if (value == null || !value.matches("[0-9]{1,10}")) return -1;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return -1; }
    }
}
