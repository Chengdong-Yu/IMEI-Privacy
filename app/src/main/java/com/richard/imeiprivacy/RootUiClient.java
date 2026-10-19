package com.richard.imeiprivacy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** The UI issues only fixed commands, never arbitrary shell input. */
final class RootUiClient {
    private static final String CONTROL = "/data/adb/modules/imei_privacy/control.sh";
    private static volatile boolean denied;
    private static volatile boolean authorized;

    static final class Result {
        final String text;
        final String error;
        Result(String text, String error) { this.text = text; this.error = error; }
    }

    static boolean authorized() { return authorized && !denied; }
    static boolean denied() { return denied; }

    static Result run(String action) {
        if (!action.equals("status") && !action.equals("recheck") && !action.equals("reboot-confirmed")
                && !action.equals("set-enabled 0") && !action.equals("set-enabled 1")) {
            return new Result("", "INVALID_COMMAND");
        }
        if (denied) return new Result("", "ROOT_DENIED");
        String command = "if [ \"$(id -u)\" = 0 ]; then printf 'root=1\\n'; "
                + "if [ -f '" + CONTROL + "' ]; then sh '" + CONTROL + "' " + action
                + " " + (android.os.Process.myUid() / 100000)
                + "; else printf 'error=MODULE_MISSING\\n'; exit 6; fi; else exit 77; fi";
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream input = process.getInputStream();
            Thread reader = new Thread(() -> {
                byte[] buffer = new byte[1024];
                try (InputStream stream = input) {
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        synchronized (output) {
                            int remaining = 8192 - output.size();
                            if (remaining > 0) output.write(buffer, 0, Math.min(remaining, count));
                        }
                    }
                } catch (Exception ignored) { /* No shell output is logged. */ }
            }, "ImeiPrivacy-RootOutput");
            reader.setDaemon(true);
            reader.start();
            boolean completed = process.waitFor(25, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                denied = !authorized;
                return new Result("", "ROOT_TIMEOUT");
            }
            reader.join(1000);
            String text;
            synchronized (output) { text = output.toString(StandardCharsets.UTF_8.name()); }
            boolean root = text.matches("(?s).*?(?:^|\\n)root=1(?:\\r?\\n|$).*?");
            if (!root) {
                denied = true;
                authorized = false;
                return new Result("", "ROOT_DENIED");
            }
            authorized = true;
            if (process.exitValue() != 0) {
                if (text.contains("error=MODULE_MISSING")) return new Result("", "MODULE_MISSING");
                return new Result("", "COMMAND_FAILED");
            }
            if (action.equals("reboot-confirmed") && !text.contains("\nreboot_requested=1\n")) {
                return new Result("", "REBOOT_FAILED");
            }
            return new Result(text, null);
        } catch (Exception ignored) {
            if (!authorized) denied = true;
            return new Result("", "ROOT_UNAVAILABLE");
        } finally {
            if (process != null) process.destroy();
        }
    }
}
