package com.richard.imeiprivacy;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Actual dispatch tests with originals stopped; results contain codes, never identifiers. */
final class FindDeviceSelfTest {
    private static final String TAG = "ImeiPrivacy/Health";
    private static final HealthLease LEASE = new HealthLease();
    private static volatile boolean started;
    private static volatile ApplicationInfo applicationInfo;
    private static volatile String lastLog = "";
    private static String stage = "INIT";

    private FindDeviceSelfTest() {}

    static String property(String key, String fallback) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getDeclaredMethod("get", String.class, String.class).invoke(null, key, fallback);
            return value instanceof String ? (String) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static boolean permitsNetwork() {
        try {
            if ("off".equals(property("persist.sys.ipriv.guard_mode", ""))) return true;
            return "1".equals(property("persist.sys.ipriv.guard_ready", "0"))
                    && LEASE.permits(now(), property("persist.sys.ipriv.guard_nonce", ""));
        } catch (Throwable ignored) {
            LEASE.failed("GATE_CHECK_FAILED");
            return false;
        }
    }

    static void failed(String code) {
        LEASE.failed(code);
        publish();
    }

    static void install(XC_LoadPackage.LoadPackageParam param, DexHookPlan plan, boolean complete) {
        try {
            applicationInfo = (ApplicationInfo) param.getClass().getField("appInfo").get(param);
            Class<?> application = XposedHelpers.findClassIfExists("android.app.Application", param.classLoader);
            if (application != null) {
                HookRegistry.methods("health-start", application, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        if (hook.args.length > 0 && hook.args[0] instanceof Context) {
                            Context context = (Context) hook.args[0];
                            if ("com.xiaomi.finddevice".equals(context.getPackageName())) {
                                start(context, param.classLoader, plan, complete);
                            }
                        }
                    }
                }, "attach");
            }
            // Handles late load callbacks without starting any application.
            Class<?> helper = XposedHelpers.findClassIfExists("android.app.AndroidAppHelper", param.classLoader);
            if (helper != null) {
                Object current = helper.getDeclaredMethod("currentApplication").invoke(null);
                if (current instanceof Application && "com.xiaomi.finddevice".equals(((Application) current).getPackageName())) {
                    start((Application) current, param.classLoader, plan, complete);
                }
            }
        } catch (Throwable ignored) {
            failed("SELFTEST_INIT_FAILED");
        }
    }

    private static synchronized void start(Context context, ClassLoader loader, DexHookPlan plan, boolean complete) {
        if (started) return;
        started = true;
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ImeiPrivacy-Health");
            thread.setDaemon(true);
            return thread;
        }).scheduleWithFixedDelay(() -> {
            String nonce = property("persist.sys.ipriv.guard_nonce", "");
            LEASE.begin(nonce);
            try {
                if (!LEASE.latchedFailure()) {
                    require(nonce.matches("[0-9a-f]{32}"), "NO_KERNEL_CHALLENGE");
                    require("1".equals(property("persist.sys.ipriv.guard_ready", "0")), "KERNEL_GUARD_NOT_READY");
                    require(complete, "CRITICAL_HOOK_MISSING");
                    verify(context, loader, plan);
                    LEASE.passed(now());
                }
            } catch (CheckFailure failure) {
                LEASE.failed(failure.code);
            } catch (Throwable error) {
                LEASE.failed("PROBE_" + stage + "_FAILED");
                XposedBridge.log(TAG + ": probe error type=" + error.getClass().getSimpleName());
            }
            publish();
        }, 0, 5, TimeUnit.SECONDS);
    }

    private static void verify(Context context, ClassLoader loader, DexHookPlan plan) throws Throwable {
        stage = "PROFILE";
        Map<String, String> profile = FindDevicePrivacyHook.profileForSelfTest();
        for (Map.Entry<String, String> expected : profile.entrySet()) {
            String actual = property("persist.sys.ipriv." + expected.getKey(), "");
            require(expected.getKey().startsWith("meid") ? expected.getValue().equalsIgnoreCase(actual)
                    : expected.getValue().equals(actual), "PROFILE_MISMATCH");
        }
        stage = "NETWORK_GATE";
        FindDeviceNetworkGate.verifyDispatch();
        stage = "FRAMEWORK_IDS";
        Class<?> telephony = Class.forName("android.telephony.TelephonyManager", false, loader);
        for (int slot = 0; slot < 2; slot++) {
            checkString(telephony.getDeclaredMethod("getImei", int.class), new Object[]{slot}, profile.get("imei" + slot));
            checkString(telephony.getDeclaredMethod("getMeid", int.class), new Object[]{slot}, profile.get("meid" + slot));
        }
        Class<?> secure = Class.forName("android.provider.Settings$Secure", false, loader);
        checkString(secure.getDeclaredMethod("getString", android.content.ContentResolver.class, String.class),
                new Object[]{context.getContentResolver(), "android_id"}, profile.get("androidid"));
        checkString(Build.class.getDeclaredMethod("getSerial"), new Object[0], profile.get("serial"));
        require(profile.get("serial").equals(Build.SERIAL), "BUILD_SERIAL_NOT_MASKED");
        Class<?> properties = Class.forName("android.os.SystemProperties", false, loader);
        checkString(properties.getDeclaredMethod("get", String.class, String.class),
                new Object[]{"ro.serialno", ""}, profile.get("serial"));

        checkString(telephony.getDeclaredMethod("getSubscriberId"), new Object[0], profile.get("imsi0"));
        checkString(telephony.getDeclaredMethod("getSimSerialNumber"), new Object[0], profile.get("iccid0"));
        Class<?> oaid = XposedHelpers.findClassIfExists("com.android.id.impl.IdProviderImpl", loader);
        if (oaid != null) checkString(oaid.getDeclaredMethod("getOAID", Context.class), new Object[]{context}, profile.get("oaid"));
        Class<?> oneTrack = XposedHelpers.findClassIfExists("com.xiaomi.onetrack.OneTrack", loader);
        if (oneTrack != null) {
            checkString(oneTrack.getDeclaredMethod("getInstanceId"), new Object[0], profile.get("instance"));
            checkString(oneTrack.getDeclaredMethod("getOAID", Context.class), new Object[]{context}, profile.get("oaid"));
            Class<?> configuration = Class.forName("com.xiaomi.onetrack.Configuration", false, loader);
            for (String flag : new String[]{"isGAIDEnable", "isIMEIEnable", "isIMSIEnable"}) {
                Method method = configuration.getDeclaredMethod(flag);
                HookProbe.Result result = HookProbe.invoke(method, receiver(method), new Object[0], true);
                require(result.error == null && Boolean.FALSE.equals(result.value), "TELEMETRY_ID_FLAG_NOT_DISABLED");
            }
        }
        stage = "LOCATION";
        for (String coordinate : new String[]{"getLatitude", "getLongitude"}) {
            Method method = android.location.Location.class.getDeclaredMethod(coordinate);
            HookProbe.Result result = HookProbe.invoke(method, receiver(method), new Object[0], 42.0d);
            require(result.error == null && Double.valueOf(0).equals(result.value), "LOCATION_NOT_MASKED");
        }

        stage = "CORE";
        require(!plan.infos.isEmpty() && !plan.credentials.isEmpty()
                && !plan.cloudProviders.isEmpty() && !plan.requests.isEmpty() && !plan.transports.isEmpty(), "DISCOVERY_INCOMPLETE");
        for (DexHookPlan.Info info : plan.infos) {
            Class<?> type = DexHookPlan.type(info.owner, loader);
            Map<String, String> originals = new HashMap<>();
            for (String name : info.fields.values()) originals.put(name, "IPRIV_ORIGINAL_FIXTURE");
            int constructors = 0;
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                HookProbe.Result result = HookProbe.invoke(constructor, null,
                        arguments(constructor.getParameterTypes(), context), null, null, originals, false);
                checkInfo(result.receiver, info);
                require(result.error == null, "CONSTRUCTOR_PROBE_ERROR");
                constructors++;
            }
            require(constructors > 0, "NO_CONSTRUCTOR_PROBE");
            for (DexHookPlan.MethodId id : info.factories) {
                Object seed = HookProbe.allocate(type);
                for (Map.Entry<String, String> field : originals.entrySet()) {
                    Field member = type.getDeclaredField(field.getKey());
                    member.setAccessible(true);
                    member.set(seed, field.getValue());
                }
                Method method = id.resolve(loader);
                HookProbe.Result result = HookProbe.invoke(method, receiver(method),
                        arguments(method.getParameterTypes(), context), seed);
                require(result.error == null, "CACHE_PROBE_ERROR");
                checkInfo(result.value, info);
            }
            require(info.readers.containsKey(DexHookPlan.Role.CORE_ID)
                    && !info.readers.get(DexHookPlan.Role.CORE_ID).isEmpty(), "CORE_READER_NOT_FOUND");
            for (Map.Entry<DexHookPlan.Role, java.util.Set<DexHookPlan.MethodId>> readers : info.readers.entrySet()) {
                for (DexHookPlan.MethodId id : readers.getValue()) {
                    Method method = id.resolve(loader);
                    checkString(method, arguments(method.getParameterTypes(), context), FindDevicePrivacyHook.roleValue(readers.getKey()));
                }
            }
        }
        stage = "FID";
        for (DexHookPlan.MethodId id : plan.credentials) checkString(id.resolve(loader), new Object[0], profile.get("fid"));
        for (DexHookPlan.MethodId id : plan.fidCaches) {
            Method method = id.resolve(loader);
            checkString(method, arguments(method.getParameterTypes(), context), profile.get("fid"));
        }
        stage = "CLOUD";
        for (DexHookPlan.MethodId id : plan.cloudProviders) {
            Method method = id.resolve(loader);
            Bundle seed = new Bundle();
            seed.putString("result_id", "IPRIV_ORIGINAL_FIXTURE");
            HookProbe.Result result = HookProbe.invoke(method, receiver(method), new Object[]{"getCloudId", null, null}, seed);
            require(result.error == null && result.value instanceof Bundle
                    && FindDevicePrivacyHook.roleValue(DexHookPlan.Role.CORE_ID).equals(((Bundle) result.value).getString("result_id")), "CLOUD_ID_NOT_MASKED");
        }
        stage = "REQUESTS";
        for (DexHookPlan.MethodId id : plan.requests) {
            Method method = id.resolve(loader);
            Object[] args = arguments(method.getParameterTypes(), context);
            for (int index = 0; index < args.length; index++) {
                if (Map.class.isAssignableFrom(method.getParameterTypes()[index])) {
                    args[index] = method.getParameterTypes()[index] == TreeMap.class ? new TreeMap<>(fixture()) : fixture();
                }
            }
            HookProbe.Result result = HookProbe.invoke(method, receiver(method), args, null);
            require(result.error == null, "REQUEST_PROBE_ERROR");
            for (Object value : result.args) if (value instanceof Map) checkMap((Map<?, ?>) value);
        }
        stage = "TRANSPORTS";
        FindDeviceRequestSanitizer policy = new FindDeviceRequestSanitizer(FindDevicePrivacyHook.requestReplacements());
        for (DexHookPlan.MethodId id : plan.transports) {
            Method method = id.resolve(loader);
            FindDeviceTransportLayout layout = FindDeviceTransportLayout.of(method);
            require(layout != null, "TRANSPORT_LAYOUT_UNKNOWN");
            Object[] args = arguments(method.getParameterTypes(), context);
            String url = "https://privacy-selftest.invalid/?cloudsp_sn=IPRIV_ORIGINAL_FIXTURE&cloudsp_fid=IPRIV_ORIGINAL_FIXTURE&cloudsp_userId=IPRIV_ACCOUNT";
            args[layout.url] = url;
            if (layout.headers >= 0) args[layout.headers] = Collections.singletonMap("Cookie", Collections.singletonList("IPRIV_ACCOUNT_COOKIE"));
            if (layout.body >= 0) args[layout.body] = fixture();
            Object expectedHeaders = layout.headers >= 0 ? args[layout.headers] : null;
            HookProbe.Result result = HookProbe.invoke(method, receiver(method), args, null);
            require(result.error == null && policy.url(url).equals(result.args[layout.url]), "TRANSPORT_QUERY_NOT_MASKED");
            if (layout.body >= 0) {
                require(result.args[layout.body] instanceof Map, "TRANSPORT_BODY_TYPE");
                checkMap((Map<?, ?>) result.args[layout.body]);
            }
            if (layout.headers >= 0) require(expectedHeaders.equals(result.args[layout.headers]), "ACCOUNT_HEADER_CHANGED");
        }
    }

    private static Map<String, String> fixture() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : FindDevicePrivacyHook.requestReplacements().keySet()) result.put(key, "IPRIV_ORIGINAL_FIXTURE");
        result.put("cloudsp_userId", "IPRIV_ACCOUNT");
        result.put("serviceToken", "IPRIV_ACCOUNT_TOKEN");
        return result;
    }

    private static void checkMap(Map<?, ?> actual) throws CheckFailure {
        for (Map.Entry<String, String> expected : FindDevicePrivacyHook.requestReplacements().entrySet()) {
            require(expected.getValue().equals(actual.get(expected.getKey())), "REQUEST_FIELD_NOT_MASKED");
        }
        require("IPRIV_ACCOUNT".equals(actual.get("cloudsp_userId"))
                && "IPRIV_ACCOUNT_TOKEN".equals(actual.get("serviceToken")), "ACCOUNT_FIELD_CHANGED");
    }

    private static void checkInfo(Object value, DexHookPlan.Info info) throws Throwable {
        require(value != null, "INFO_PROBE_EMPTY");
        for (Map.Entry<DexHookPlan.Role, String> entry : info.fields.entrySet()) {
            Field field = value.getClass().getDeclaredField(entry.getValue());
            field.setAccessible(true);
            require(FindDevicePrivacyHook.roleValue(entry.getKey()).equals(field.get(value)), "INFO_FIELD_NOT_MASKED");
        }
    }

    private static void checkString(Method method, Object[] args, String expected) throws Throwable {
        HookProbe.Result result = HookProbe.invoke(method, receiver(method), args, "IPRIV_ORIGINAL_FIXTURE");
        require(result.error == null && expected != null && expected.equals(result.value), "IDENTIFIER_NOT_MASKED");
    }

    private static Object receiver(Method method) throws ReflectiveOperationException {
        return Modifier.isStatic(method.getModifiers()) ? null : HookProbe.allocate(method.getDeclaringClass());
    }

    private static Object[] arguments(Class<?>[] types, Context context) {
        Object[] args = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (type == Context.class) args[index] = context;
            else if (type == boolean.class) args[index] = false;
            else if (type == int.class) args[index] = 0;
            else if (type == long.class) args[index] = 0L;
            else if (type == float.class) args[index] = 0f;
            else if (type == double.class) args[index] = 0d;
            else if (type == byte.class) args[index] = (byte) 0;
            else if (type == short.class) args[index] = (short) 0;
            else if (type == char.class) args[index] = (char) 0;
            else if (type == String.class) args[index] = "IPRIV_FIXTURE";
        }
        return args;
    }

    private static long now() { return SystemClock.elapsedRealtime() / 1000; }

    private static synchronized void publish() {
        ApplicationInfo info = applicationInfo;
        if (info == null || info.deviceProtectedDataDir == null) return;
        String nonce = property("persist.sys.ipriv.guard_nonce", "");
        String state = LEASE.state(now(), nonce);
        String reason = LEASE.reason();
        try {
            File directory = new File(info.deviceProtectedDataDir, "files/imei_privacy_health");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException();
            android.system.Os.chmod(directory.getAbsolutePath(), 0700);
            String stat;
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/stat"))) { stat = reader.readLine(); }
            String start = HealthLease.processStartTicks(stat);
            String record = "v1 " + nonce + " " + Process.myPid() + " " + start + " " + now() + " " + state + " " + reason + "\n";
            File temporary = new File(directory, Process.myPid() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                android.system.Os.chmod(temporary.getAbsolutePath(), 0600);
                output.write(record.getBytes(StandardCharsets.US_ASCII));
                output.getFD().sync();
            }
            if (!temporary.renameTo(new File(directory, Process.myPid() + ".state"))) throw new java.io.IOException();
        } catch (Throwable ignored) {
            LEASE.failed("HEALTH_REPORT_FAILED");
            state = "FAIL";
            reason = "HEALTH_REPORT_FAILED";
        }
        String log = state + "/" + reason;
        if (!log.equals(lastLog)) {
            lastLog = log;
            XposedBridge.log(TAG + ": self-test=" + state + " reason=" + reason);
        }
    }

    private static void require(boolean condition, String code) throws CheckFailure {
        if (!condition) throw new CheckFailure(code);
    }

    private static final class CheckFailure extends Exception {
        final String code;
        CheckFailure(String code) { this.code = code; }
    }
}
