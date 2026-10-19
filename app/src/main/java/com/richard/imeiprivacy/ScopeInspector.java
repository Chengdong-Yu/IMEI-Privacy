package com.richard.imeiprivacy;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Invoked by root app_process. Reads only this module's rows; never edits LSPosed. */
public final class ScopeInspector {
    private static final String DATABASE = "/data/adb/lspd/config/modules_config.db";
    private static final String MODULE = "com.richard.imeiprivacy";

    private ScopeInspector() {}

    public static void main(String[] args) {
        int user = -1;
        ScopeStatus result = ScopeStatus.unknown("INVALID_USER");
        try {
            if (args.length == 1 && args[0].matches("[0-9]{1,5}")) {
                user = Integer.parseInt(args[0]);
                if (user <= 21473 && Process.myUid() == 0) result = inspect(user);
                else result = ScopeStatus.unknown("ACCESS_DENIED");
            }
        } catch (Throwable ignored) { result = ScopeStatus.unknown("READ_FAILED"); }
        System.out.println("scope_state=" + result.state);
        System.out.println("scope_reason=" + result.reason);
        System.out.println("scope_finddevice=" + (result.findDevice ? 1 : 0));
        System.out.println("scope_phone=" + (result.phone ? 1 : 0));
        System.out.println("scope_system=" + (result.system ? 1 : 0));
        System.out.println("scope_user=" + Math.max(0, user));
        System.out.println("scope_checked_uptime=" + SystemClock.elapsedRealtime() / 1000);
    }

    private static ScopeStatus inspect(int user) {
        File file = new File(DATABASE);
        if (!file.isFile()) return ScopeStatus.unknown("DATABASE_UNAVAILABLE");
        // A null DatabaseErrorHandler would let Android delete a corrupt DB in
        // some paths. Our read-only probe must never perform recovery/deletion.
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(DATABASE, null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ignored -> { })) {
            if (!hasColumns(database, "modules", "mid", "module_pkg_name", "enabled")
                    || !hasColumns(database, "scope", "mid", "app_pkg_name", "user_id"))
                return ScopeStatus.unknown("UNSUPPORTED_SCHEMA");
            boolean registered = false, enabled = false, findDevice = false, phone = false, system = false;
            String query = "SELECT modules.enabled, scope.app_pkg_name, scope.user_id FROM modules "
                    + "LEFT JOIN scope ON modules.mid=scope.mid WHERE modules.module_pkg_name=?";
            try (Cursor rows = database.rawQuery(query, new String[]{MODULE})) {
                while (rows.moveToNext()) {
                    registered = true;
                    if (rows.isNull(0)) return ScopeStatus.unknown("INVALID_CONFIG");
                    int flag = rows.getInt(0);
                    if (flag != 0 && flag != 1) return ScopeStatus.unknown("UNSUPPORTED_SCHEMA");
                    enabled = flag == 1;
                    if (rows.isNull(1) || rows.isNull(2)) continue;
                    if (rows.getType(1) != Cursor.FIELD_TYPE_STRING || rows.getType(2) != Cursor.FIELD_TYPE_INTEGER)
                        return ScopeStatus.unknown("INVALID_CONFIG");
                    String target = rows.getString(1);
                    int scopeUser = rows.getInt(2);
                    if ("com.xiaomi.finddevice".equals(target) && scopeUser == user) findDevice = true;
                    if ("com.android.phone".equals(target) && scopeUser == 0) phone = true;
                    // Both LSPosed's original spelling and migrated pseudo-package.
                    if (("system".equals(target) || "android".equals(target)) && scopeUser == 0) system = true;
                }
            }
            return ScopeStatus.configured(registered, enabled, findDevice, phone, system);
        } catch (Throwable ignored) {
            return ScopeStatus.unknown("READ_FAILED");
        }
    }

    private static boolean hasColumns(SQLiteDatabase database, String table, String... required) {
        Set<String> columns = new HashSet<>();
        // Only fixed table constants from this class are passed, not UI text.
        try (Cursor rows = database.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int name = rows.getColumnIndexOrThrow("name");
            while (rows.moveToNext()) columns.add(rows.getString(name));
        }
        for (String column : required) if (!columns.contains(column)) return false;
        return true;
    }
}
