package com.richard.imeiprivacy;

/** Fail closed. A successful sample check is a short lease, not a permanent flag. */
final class HealthLease {
    static final long TTL_SECONDS = 15;
    private String nonce = "";
    private String state = "WAIT";
    private String reason = "NOT_TESTED";
    private long passedAt = -1;

    synchronized void begin(String currentNonce) {
        if (currentNonce == null) currentNonce = "";
        if (!nonce.equals(currentNonce)) {
            nonce = currentNonce;
            state = "WAIT";
            reason = "CHALLENGE_CHANGED";
            passedAt = -1;
        }
    }

    synchronized void passed(long now) {
        if ("FAIL".equals(state) || !nonce.matches("[0-9a-f]{32}")) return;
        state = "PASS";
        reason = "KNOWN_PATHS_VERIFIED";
        passedAt = now;
    }

    synchronized void failed(String code) {
        state = "FAIL";
        reason = code != null && code.length() <= 64 && code.matches("[A-Z]+(?:_[A-Z]+)+") ? code : "CHECK_ERROR";
        passedAt = -1;
    }

    synchronized boolean permits(long now, String currentNonce) {
        return nonce.equals(currentNonce) && "PASS".equals(state) && passedAt >= 0
                && now >= passedAt && now - passedAt <= TTL_SECONDS;
    }

    synchronized String state(long now, String currentNonce) {
        if (permits(now, currentNonce)) return "PASS";
        return "FAIL".equals(state) ? "FAIL" : "WAIT";
    }

    synchronized String reason() { return reason; }
    synchronized boolean latchedFailure() { return "FAIL".equals(state); }

    static String processStartTicks(String stat) {
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IllegalArgumentException("Invalid process stat");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20 || !fields[19].matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid process start time");
        }
        return fields[19];
    }
}
