package com.richard.imeiprivacy;

import android.os.Bundle;
import android.content.pm.ApplicationInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Strict privacy profile for Xiaomi Find Device. Account identity is left
 * untouched, while device, SIM, advertising, push and location identifiers are
 * replaced. This intentionally prioritizes privacy over Find Device operation.
 */
final class FindDevicePrivacyHook {
    private static final String TAG = "ImeiPrivacy/FindDevice";
    private static final String PACKAGE_NAME = "com.xiaomi.finddevice";

    private static String androidId = "a1b2c3d4e5f67890";
    private static String oaid = "4f6a55d2-7c8e-4a91-b2d3-6e7f8091a2b3";
    private static String gaid = "7b8c69e3-8d9f-4ba2-c3e4-7f8091a2b3c4";
    private static String instanceId = "91a2b3c4-d5e6-47f8-9012-3456789abcde";
    private static String[] imsi = {"001010123456789", "001010987654321"};
    private static String[] iccid = {"8986001234567890123", "8986009876543210987"};
    private static String serial = "900154359217";
    private static String mac = "02:12:34:56:78:9A";
    private static String fid = "2a3b4c5d6e7f8091a2b3c4d5e6f70819";
    private static String pushToken =
            "privacy000000000000000000000000000000000000000000000000000000000";
    private static String cloudId = sha256(androidId + ":cloud").substring(0, 32) + "@0";
    private static String deviceId = sha256(cloudId).substring(0, 16);
    private static FindDeviceRequestSanitizer requestSanitizer;
    private static final Set<ClassLoader> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<String> HITS = ConcurrentHashMap.newKeySet();
    private static int installedHooks;

    private FindDevicePrivacyHook() {}

    static synchronized void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!PACKAGE_NAME.equals(loadPackageParam.packageName)) {
            return;
        }

        ClassLoader loader = loadPackageParam.classLoader;
        if (!INSTALLED.add(loader)) return;
        loadProfile();
        requestSanitizer = new FindDeviceRequestSanitizer(requestReplacements());
        int before = installedHooks;
        step("settings", () -> { hookSettingsIds(loader); return 0; });
        step("anonymous", () -> { hookAnonymousIds(loader); return 0; });
        step("sim", () -> { hookTelephonyPersonalData(loader); return 0; });
        DexHookPlan plan = discover(loadPackageParam);
        int core = step("core", () -> hookCoreDeviceInfo(loader, plan));
        int credentials = step("fid", () -> hookDeviceCredential(loader, plan));
        int cloud = step("cloud", () -> hookCloudIdProvider(loader, plan));
        int requests = step("requests", () -> hookFindDeviceRequests(loader, plan));
        int transport = step("transport", () -> hookTransport(loader, plan));
        step("telemetry", () -> { hookOneTrack(loader); return 0; });
        step("location", () -> { hookLocationAndRadio(loader); return 0; });
        step("inventory", () -> { hookPackageInventory(loader); return 0; });
        XposedBridge.log(TAG + ": registered=" + (installedHooks - before)
                + " core=" + core + " fid=" + credentials + " cloud=" + cloud
                + " requests=" + requests + " transport=" + transport
                + " process=" + loadPackageParam.processName);
        if (core == 0 || credentials == 0 || cloud == 0 || requests == 0 || transport == 0) {
            XposedBridge.log(TAG + ": WARNING incomplete compatibility; privacy is NOT verified");
        }
        FindDeviceSelfTest.install(loadPackageParam, plan,
                core > 0 && credentials > 0 && cloud > 0 && requests > 0 && transport > 0);
    }

    private static DexHookPlan discover(XC_LoadPackage.LoadPackageParam param) {
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            // Reflection avoids embedding a mismatched compile-only stub field
            // descriptor. No package version, ROM build or obfuscated name is used.
            ApplicationInfo info = (ApplicationInfo) param.getClass().getField("appInfo").get(param);
            List<File> apks = new ArrayList<>();
            apks.add(new File(info.sourceDir));
            if (info.splitSourceDirs != null) {
                for (String path : info.splitSourceDirs) apks.add(new File(path));
            }
            DexHookPlan plan = DexSemanticDiscovery.scan(apks);
            XposedBridge.log(TAG + ": discovered dex=" + plan.dexFiles + " classes=" + plan.classes
                    + " info=" + plan.infos.size() + " fid=" + plan.credentials.size()
                    + " cache=" + plan.fidCaches.size() + " cloud=" + plan.cloudProviders.size()
                    + " requests=" + plan.requests.size() + " transport=" + plan.transports.size()
                    + " rejected=" + plan.rejected + " ms="
                    + (android.os.SystemClock.elapsedRealtime() - started));
            return plan;
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": WARNING semantic discovery failed ("
                    + error.getClass().getSimpleName() + "); only platform hooks available");
            return new DexHookPlan();
        }
    }

    private static int step(String name, java.util.function.IntSupplier operation) {
        try {
            return operation.getAsInt();
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": WARNING group=" + name + " failed ("
                    + error.getClass().getSimpleName() + ")");
            return 0;
        }
    }

    private static void loadProfile() {
        androidId = property("persist.sys.ipriv.androidid", androidId);
        oaid = property("persist.sys.ipriv.oaid", oaid);
        gaid = property("persist.sys.ipriv.gaid", gaid);
        instanceId = property("persist.sys.ipriv.instance", instanceId);
        imsi = new String[]{
                property("persist.sys.ipriv.imsi0", imsi[0]),
                property("persist.sys.ipriv.imsi1", imsi[1])};
        iccid = new String[]{
                property("persist.sys.ipriv.iccid0", iccid[0]),
                property("persist.sys.ipriv.iccid1", iccid[1])};
        serial = property("persist.sys.ipriv.serial", serial);
        mac = property("persist.sys.ipriv.mac", mac);
        fid = property("persist.sys.ipriv.fid", fid);
        pushToken = property("persist.sys.ipriv.push", pushToken);
        cloudId = sha256(androidId + ":cloud").substring(0, 32) + "@0";
        deviceId = sha256(cloudId).substring(0, 16);
    }

    private static String property(String key, String fallback) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getDeclaredMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, fallback);
            return value instanceof String && !((String) value).isEmpty()
                    ? (String) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void hookSettingsIds(ClassLoader loader) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                for (Object argument : param.args) {
                    if (argument instanceof String
                            && "android_id".equalsIgnoreCase((String) argument)) {
                        param.setResult(androidId);
                        hit("settings-id");
                        return;
                    }
                }
            }
        };
        hook(loader, "android.provider.Settings$Secure", callback, "getString", "getStringForUser");
        hook(loader, "android.provider.Settings$System", callback, "getString", "getStringForUser");
        hook(loader, "android.os.Build", new StringResult(serial), "getSerial");
        // Some applications read this field directly instead of getSerial().
        Class<?> build = XposedHelpers.findClassIfExists("android.os.Build", loader);
        setField(build, "SERIAL", serial);
        hook(loader, "android.os.SystemProperties", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                String key = (String) param.args[0];
                if ("ro.serialno".equals(key) || "ro.boot.serialno".equals(key)
                        || "ro.vendor.oem.serialno".equals(key)) {
                    param.setResult(serial);
                    hit("serial-property");
                }
            }
        }, "get");
    }

    private static void hookAnonymousIds(ClassLoader loader) {
        Class<?> provider = XposedHelpers.findClassIfExists(
                "com.android.id.impl.IdProviderImpl", loader);
        if (provider != null) {
            hookClass(provider, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.method instanceof Method)
                            || ((Method) param.method).getReturnType() != String.class) {
                        return;
                    }
                    String name = param.method.getName().toLowerCase();
                    if (name.contains("oaid")) {
                        param.setResult(oaid);
                    } else if (name.contains("vaid")) {
                        param.setResult(sha256(oaid + ":vaid").substring(0, 32));
                    } else if (name.contains("aaid")) {
                        param.setResult(sha256(oaid + ":aaid").substring(0, 32));
                    } else if (name.contains("udid")) {
                        param.setResult(sha256(oaid + ":udid").substring(0, 32));
                    }
                }
            }, "getOAID", "getVAID", "getAAID", "getUDID");
        }
    }

    private static void hookTelephonyPersonalData(ClassLoader loader) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.method instanceof Method)
                        || ((Method) param.method).getReturnType() != String.class) {
                    return;
                }
                String name = param.method.getName().toLowerCase();
                int slot = slot(param.args);
                if (name.contains("subscriber")) {
                    param.setResult(imsi[slot]);
                } else if (name.contains("simserial") || name.contains("iccid")) {
                    param.setResult(iccid[slot]);
                } else if (name.contains("line1") || name.contains("phonenumber")) {
                    param.setResult(slot == 0 ? "+10000000001" : "+10000000002");
                } else if (name.contains("operator")) {
                    param.setResult("00101");
                } else if (name.contains("countryiso")) {
                    param.setResult("xx");
                }
            }
        };
        String[] classes = {
                "android.telephony.TelephonyManager",
                "android.telephony.MiuiTelephonyManagerStub$IMiuiTelephonyManager",
                "miui.telephony.TelephonyManager"
        };
        String[] methods = {
                "getSubscriberId", "getSubscriberIdForSlot",
                "getSubscriberIdForSubscription", "getSimSerialNumber",
                "getSimSerialNumberForSlot", "getSimSerialNumberForSubscription",
                "getLine1Number", "getLine1NumberForSlot",
                "getLine1NumberForSubscription", "getPhoneNumber",
                "getNetworkOperator", "getNetworkOperatorForSlot",
                "getNetworkOperatorForSubscription", "getSimOperator",
                "getSimOperatorForSlot", "getSimOperatorForSubscription",
                "getNetworkCountryIso", "getNetworkCountryIsoForSlot",
                "getSimCountryIso", "getSimCountryIsoForSlot"
        };
        for (String className : classes) {
            hook(loader, className, callback, methods);
        }
    }

    private static int hookCoreDeviceInfo(ClassLoader loader, DexHookPlan plan) {
        int before = installedHooks;
        for (DexHookPlan.Info info : plan.infos) {
            Class<?> infoClass;
            try {
                infoClass = DexHookPlan.type(info.owner, loader);
            } catch (ReflectiveOperationException error) {
                continue;
            }
            if (!FindDeviceInfoSchema.matches(infoClass, info.fields)) continue;
            installedHooks += HookRegistry.constructors("finddevice", infoClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            maskDeviceInfo(param.thisObject, info);
                        }
                    });
            for (DexHookPlan.MethodId factory : info.factories) {
                installMethod(loader, factory, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        maskDeviceInfo(param.getResult(), info);
                    }
                });
            }
            for (Map.Entry<DexHookPlan.Role, Set<DexHookPlan.MethodId>> entry : info.readers.entrySet()) {
                for (DexHookPlan.MethodId reader : entry.getValue()) {
                    installMethod(loader, reader, new StringResult(roleValue(entry.getKey())));
                }
            }
        }
        return installedHooks - before;
    }

    static String roleValue(DexHookPlan.Role role) {
        switch (role) {
            case DEVICE_ID: return deviceId;
            case CORE_ID: return cloudId;
            case SERIAL: return serial;
            case MAC: return mac;
            case MODEL: return "Privacy Device";
            default: throw new IllegalArgumentException();
        }
    }

    private static void maskDeviceInfo(Object target, DexHookPlan.Info info) {
        if (target == null) return;
        for (Map.Entry<DexHookPlan.Role, String> field : info.fields.entrySet()) {
            if (!setField(target, field.getValue(), roleValue(field.getKey()))) {
                FindDeviceSelfTest.failed("INFO_FIELD_WRITE_FAILED");
            }
        }
        hit("core-info");
    }

    private static int hookDeviceCredential(ClassLoader loader, DexHookPlan plan) {
        int before = installedHooks;
        // Only FID is replaced. Owner ID, stored private keys and sign() are not modified.
        XC_MethodHook replacement = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(fid);
                hit("fid");
            }
        };
        for (DexHookPlan.MethodId method : plan.credentials) {
            installMethod(loader, method, replacement);
        }
        int getters = installedHooks - before;
        for (DexHookPlan.MethodId method : plan.fidCaches) {
            installMethod(loader, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(fid);
                    hit("fid-cache");
                }
            });
        }
        hook(loader, "android.app.SharedPreferencesImpl", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && "device_fid".equals(param.args[0])) {
                    param.setResult(fid);
                    hit("fid-preference");
                }
            }
        }, "getString");
        // Cache hooks alone do not qualify as a working credential adapter.
        return getters;
    }

    private static int hookCloudIdProvider(ClassLoader loader, DexHookPlan plan) {
        // After-hook preserves the provider's caller/signature authorization.
        int before = installedHooks;
        for (DexHookPlan.MethodId method : plan.cloudProviders) {
            installMethod(loader, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() instanceof Bundle && param.args.length > 0
                                && "getCloudId".equals(param.args[0])) {
                            Bundle result = new Bundle((Bundle) param.getResult());
                            if (result.containsKey("result_id")) {
                                result.putString("result_id", cloudId);
                                param.setResult(result);
                                hit("cloud-provider");
                            }
                        }
                    }
                });
        }
        return installedHooks - before;
    }

    private static int hookFindDeviceRequests(ClassLoader loader, DexHookPlan plan) {
        int before = installedHooks;
        XC_MethodHook maps = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!HookProbe.active(param.method) && !FindDeviceSelfTest.permitsNetwork()) {
                    param.setThrowable(new java.io.IOException(FindDeviceNetworkGate.DENIED));
                    return;
                }
                try {
                Class<?>[] types = ((Method) param.method).getParameterTypes();
                for (int index = 0; index < param.args.length; index++) {
                    if (Map.class.isAssignableFrom(types[index]) && param.args[index] instanceof Map) {
                        Map<Object, Object> copy = requestSanitizer.copyMap((Map<?, ?>) param.args[index]);
                        if (types[index].isInstance(copy)) {
                            param.args[index] = copy;
                        } else if (param.args[index] instanceof java.util.TreeMap) {
                            @SuppressWarnings("unchecked") Map<Object, Object> sorted =
                                    (Map<Object, Object>) ((java.util.TreeMap<?, ?>) param.args[index]).clone();
                            sorted.clear();
                            sorted.putAll(copy);
                            param.args[index] = sorted;
                        } else {
                            FindDeviceSelfTest.failed("REQUEST_MAP_LAYOUT");
                            param.setThrowable(new java.io.IOException("Privacy policy: unsupported request map"));
                            hit("blocked-map-layout");
                            return;
                        }
                    }
                }
                hit("request-map");
                } catch (Throwable ignored) {
                    FindDeviceSelfTest.failed("REQUEST_SANITIZE_FAILED");
                    param.setThrowable(new java.io.IOException("IPRIV_REQUEST_SANITIZE_FAILED"));
                }
            }
        };
        for (DexHookPlan.MethodId method : plan.requests) installMethod(loader, method, maps);
        return installedHooks - before;
    }

    private static int installMethod(ClassLoader loader, DexHookPlan.MethodId method, XC_MethodHook callback) {
        try {
            int count = HookRegistry.hook("finddevice", method.resolve(loader), callback);
            installedHooks += count;
            return count;
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": WARNING discovered method unavailable ("
                    + error.getClass().getSimpleName() + ")");
            return 0;
        }
    }

    private static int hookTransport(ClassLoader loader, DexHookPlan plan) {
        // Stable boundary AFTER late FID/auth fields have been appended. Query
        // fields are rewritten individually; authentication bytes are preserved.
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!HookProbe.active(param.method) && !FindDeviceSelfTest.permitsNetwork()) {
                    param.setThrowable(new java.io.IOException(FindDeviceNetworkGate.DENIED));
                    return;
                }
                try {
                FindDeviceTransportLayout layout = FindDeviceTransportLayout.of((Method) param.method);
                if (layout == null) {
                    FindDeviceSelfTest.failed("TRANSPORT_LAYOUT_UNKNOWN");
                    param.setThrowable(new java.io.IOException(FindDeviceNetworkGate.DENIED));
                    return;
                }
                int urlIndex = layout.url;
                if (param.args.length > urlIndex && param.args[urlIndex] instanceof String) {
                    param.args[urlIndex] = requestSanitizer.url((String) param.args[urlIndex]);
                }
                if (layout.headers >= 0 && param.args[layout.headers] instanceof Map) {
                    param.args[layout.headers] = requestSanitizer.copyMap(
                            (Map<?, ?>) param.args[layout.headers]);
                }
                // XHttpClient also accepts JSON and form-encoded request bodies.
                int body = layout.body;
                if (body >= 0) {
                    Object value = param.args[body];
                    try {
                        if (value instanceof Map) {
                            param.args[body] = requestSanitizer.copyMap((Map<?, ?>) value);
                        } else if (value instanceof String) {
                            String text = (String) value;
                            if (text.trim().startsWith("{")) {
                                param.args[body] = FindDeviceJsonSanitizer.copy(
                                        new JSONObject(text), requestSanitizer).toString();
                            } else if (text.trim().startsWith("[")) {
                                param.args[body] = FindDeviceJsonSanitizer.copy(
                                        new JSONArray(text), requestSanitizer).toString();
                            } else {
                                param.args[body] = requestSanitizer.form(text);
                            }
                        } else if (value instanceof JSONObject || value instanceof JSONArray) {
                            param.args[body] = FindDeviceJsonSanitizer.copy(value, requestSanitizer);
                        }
                    } catch (org.json.JSONException error) {
                        FindDeviceSelfTest.failed("INVALID_REQUEST_JSON");
                        // Throwing from an Xposed callback would run the original
                        // method. Explicitly cancel malformed JSON requests instead.
                        param.setThrowable(new java.io.IOException("Privacy policy: invalid request JSON"));
                        hit("blocked-invalid-json");
                    }
                }
                hit("http-boundary");
                } catch (Throwable ignored) {
                    FindDeviceSelfTest.failed("TRANSPORT_SANITIZE_FAILED");
                    param.setThrowable(new java.io.IOException("IPRIV_TRANSPORT_SANITIZE_FAILED"));
                }
            }
        };
        int before = installedHooks;
        for (DexHookPlan.MethodId method : plan.transports) installMethod(loader, method, callback);
        return installedHooks - before;
    }

    static Map<String, String> requestReplacements() {
        Map<String, String> map = new HashMap<>();
        add(map, deviceId, "cloudsp_devId", "cloudsp_cpuId", "cloudsp_deviceLyraDid");
        add(map, cloudId, "cloudsp_imei");
        add(map, ImeiPrivacyHook.fakeImei(0), "imei", "imei1");
        add(map, ImeiPrivacyHook.fakeImei(1), "imei2");
        add(map, serial, "cloudsp_sn", "serial", "serialno");
        add(map, mac, "cloudsp_mac", "cloudsp_leaMac");
        add(map, "Privacy Device", "cloudsp_model", "cloudsp_modelName", "cloudsp_marketName",
                "cloudsp_deviceName");
        add(map, "privacy", "cloudsp_product");
        add(map, fid, "cloudsp_fid", "cloudsp_tzid", "cloudsp_targetFid");
        add(map, pushToken, "cloudsp_fcmToken", "cloudsp_regId", "cloudsp_token");
        add(map, "[]", "cloudsp_phoneInfo", "cloudsp_assistInfoList", "cloudsp_hashEidList",
                "cloudsp_ePubKeyHashList", "cloudsp_advList", "cloudsp_targetList");
        add(map, "{}", "cloudsp_gpsInfo", "cloudsp_brief");
        add(map, imsi[0], "imsi");
        add(map, iccid[0], "iccid");
        add(map, androidId, "android_id", "androidId");
        add(map, oaid, "oaid");
        add(map, gaid, "gaid");
        // Intentionally no userId/cUserId/serviceToken/cookie/signature entries.
        return map;
    }

    static Map<String, String> profileForSelfTest() {
        Map<String, String> values = new HashMap<>();
        for (int slot = 0; slot < 2; slot++) {
            values.put("imei" + slot, ImeiPrivacyHook.fakeImei(slot));
            values.put("meid" + slot, ImeiPrivacyHook.fakeMeid(slot));
            values.put("imsi" + slot, imsi[slot]);
            values.put("iccid" + slot, iccid[slot]);
        }
        values.put("androidid", androidId);
        values.put("oaid", oaid);
        values.put("gaid", gaid);
        values.put("instance", instanceId);
        values.put("serial", serial);
        values.put("mac", mac);
        values.put("fid", fid);
        values.put("push", pushToken);
        return values;
    }

    private static void add(Map<String, String> map, String value, String... keys) {
        for (String key : keys) map.put(key, value);
    }

    private static void hookOneTrack(ClassLoader loader) {
        hook(loader, "com.xiaomi.onetrack.OneTrack", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String name = param.method.getName();
                if ("track".equals(name)) {
                    param.setResult(null);
                } else if ("setDisable".equals(name) && param.args.length == 1) {
                    param.args[0] = true;
                } else if ("setAccessNetworkEnable".equals(name)
                        && param.args.length > 1 && param.args[1] instanceof Boolean) {
                    param.args[1] = false;
                }
            }
        }, "track", "setAccessNetworkEnable", "setDisable");
        Class<?> oneTrack = XposedHelpers.findClassIfExists("com.xiaomi.onetrack.OneTrack", loader);
        if (oneTrack != null) {
            try {
                oneTrack.getDeclaredMethod("setDisable", boolean.class).invoke(null, true);
            } catch (ReflectiveOperationException error) {
                XposedBridge.log(TAG + ": OneTrack disable unavailable");
            }
        }
        hook(loader, "com.xiaomi.onetrack.OneTrack", new StringResult(instanceId), "getInstanceId");
        hook(loader, "com.xiaomi.onetrack.OneTrack", new StringResult(oaid), "getOAID");
        hook(loader, "com.xiaomi.onetrack.Configuration", new StringResult(instanceId), "getInstanceId");

        XC_MethodHook disabled = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.method instanceof Method
                        && ((Method) param.method).getReturnType() == boolean.class) {
                    param.setResult(false);
                }
            }
        };
        hook(loader, "com.xiaomi.onetrack.Configuration", disabled,
                "isGAIDEnable", "isIMEIEnable", "isIMSIEnable");

        // No single-letter SDK methods: platform ID hooks and the stable
        // OneTrack public controls remain effective when its internals rename.
    }

    private static void hookLocationAndRadio(ClassLoader loader) {
        XC_MethodHook location = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.method instanceof Method)) {
                    return;
                }
                Class<?> type = ((Method) param.method).getReturnType();
                String name = param.method.getName();
                if (type == double.class) {
                    param.setResult(0.0d);
                } else if (type == float.class) {
                    param.setResult("getAccuracy".equals(name) ? 9999.0f : 0.0f);
                } else if (type == String.class && "getProvider".equals(name)) {
                    param.setResult("privacy");
                }
            }
        };
        String[] locationMethods = {
                "getLatitude", "getLongitude", "getAccuracy", "getAltitude",
                "getSpeed", "getBearing", "getProvider"
        };
        hook(loader, "android.location.Location", location, locationMethods);
        hook(loader, "com.amap.api.location.AMapLocation", location, locationMethods);
        hook(loader, "com.amap.api.location.AMapLocationClient",
                new NullResult(), "startLocation");

        hook(loader, "android.net.wifi.WifiManager", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.method instanceof Method
                        && List.class.isAssignableFrom(((Method) param.method).getReturnType())) {
                    param.setResult(Collections.emptyList());
                }
            }
        }, "getScanResults");
        hook(loader, "android.net.wifi.WifiInfo", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String name = param.method.getName();
                if ("getMacAddress".equals(name)) {
                    param.setResult(mac);
                } else if ("getBSSID".equals(name)) {
                    param.setResult("02:00:00:00:00:01");
                } else if ("getSSID".equals(name)) {
                    param.setResult("<unknown ssid>");
                }
            }
        }, "getMacAddress", "getBSSID", "getSSID");
        hook(loader, "java.net.NetworkInterface", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(macBytes(mac));
            }
        }, "getHardwareAddress");

        hook(loader, "android.telephony.TelephonyManager", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Method method = (Method) param.method;
                if (List.class.isAssignableFrom(method.getReturnType())) {
                    param.setResult(Collections.emptyList());
                } else {
                    param.setResult(null);
                }
            }
        }, "getAllCellInfo", "getCellLocation");
    }

    private static void hookPackageInventory(ClassLoader loader) {
        hook(loader, "android.app.ApplicationPackageManager", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.method instanceof Method
                        && List.class.isAssignableFrom(((Method) param.method).getReturnType())) {
                    param.setResult(Collections.emptyList());
                }
            }
        }, "getInstalledPackages", "getInstalledApplications");
    }

    private static int hook(ClassLoader loader, String className,
            XC_MethodHook callback, String... methodNames) {
        Class<?> target = XposedHelpers.findClassIfExists(className, loader);
        if (target != null) {
            return hookClass(target, callback, methodNames);
        }
        return 0;
    }

    private static int hookClass(Class<?> target, XC_MethodHook callback,
            String... methodNames) {
        int count = HookRegistry.methods("finddevice", target, callback, methodNames);
        installedHooks += count;
        return count;
    }

    private static boolean setField(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target instanceof Class ? null : target, value);
                return value.equals(field.get(target instanceof Class ? null : target));
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": unable to replace field " + name + " ("
                        + throwable.getClass().getSimpleName() + ")");
                return false;
            }
        }
        return false;
    }

    private static void hit(String category) {
        if (HITS.add(category)) XposedBridge.log(TAG + ": first hit=" + category);
    }

    private static int slot(Object[] args) {
        if (args != null) {
            for (Object argument : args) {
                if (argument instanceof Integer && ((Integer) argument) == 1) {
                    return 1;
                }
            }
        }
        return 0;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    private static byte[] macBytes(String value) {
        try {
            String[] parts = value.split(":");
            byte[] result = new byte[6];
            for (int index = 0; index < result.length; index++) {
                result[index] = (byte) Integer.parseInt(parts[index], 16);
            }
            return result;
        } catch (Throwable ignored) {
            return new byte[]{2, 0, 0, 0, 0, 1};
        }
    }

    private static final class StringResult extends XC_MethodHook {
        private final String value;

        StringResult(String value) {
            this.value = value;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.method instanceof Method
                    && ((Method) param.method).getReturnType() == String.class) {
                param.setResult(value);
            }
        }
    }

    private static final class NullResult extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
        }
    }
}
