package com.richard.imeiprivacy;

import android.telephony.SubscriptionManager;
import android.os.Build;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks the server side of Android's telephony identifier APIs. The KernelSU
 * companion module masks property-based access; these hooks cover callers that
 * talk directly to the phone-sub-info Binder service.
 */
public final class ImeiPrivacyHook implements IXposedHookLoadPackage {
    private static final String TAG = "ImeiPrivacy";

    // These values intentionally match module/config.conf. Both are valid Luhn IMEIs.
    private static volatile String[] FAKE_IMEI = {
            "358240051234565",
            "358240056543218"
    };
    private static volatile String[] FAKE_MEID = {
            "A1000000000001",
            "A1000000000002"
    };

    private static final String[] TELEPHONY_CLASSES = {
            "android.telephony.TelephonyManager",
            "android.telephony.MiuiTelephonyManagerStub",
            "android.telephony.MiuiTelephonyManagerStub$IMiuiTelephonyManager",
            "android.telephony.TelephonyBaseProxy$ITelephonyBase",
            "miui.telephony.TelephonyManager",
            "com.android.internal.telephony.PhoneSubInfoController",
            "com.android.internal.telephony.Phone",
            "com.android.internal.telephony.GsmCdmaPhone",
            "com.android.internal.telephony.PhoneProxy",
            "com.android.internal.telephony.imsphone.ImsPhone",
            "com.android.internal.telephony.imsphone.ImsPhoneBase",
            "com.android.internal.telephony.IPhoneSubInfo$Stub$Proxy",
            "com.android.internal.telephony.ITelephony$Stub$Proxy",
            "com.android.phone.PhoneInterfaceManager",
            "com.android.phone.MiuiPhoneInterfaceManager",
            "com.android.phone.ImeiMeidSource",
            "com.android.phone.statistics.MiuiTelephonyStatistic",
            "com.qti.extphone.QtiImeiInfo",
            "com.qti.extphone.ExtTelephonyManager",
            "com.qti.extphone.IExtPhone$Stub$Proxy",
            "com.qti.phone.ExtTelephonyServiceImpl",
            "com.qti.phone.ExtTelephonyService$1",
            "com.qti.phone.QtiPrimaryImeiHandler",
            "com.miui.networkassistant.utils.TelephonyUtil",
            "com.miui.networkassistant.utils.DeviceUtil",
            "com.miui.daemon.mqsas.utils.DeviceUtil",
            "com.miui.daemon.mqsas.policy.filter.BaseFilter",
            "com.miui.cit.hardware.CitVersionCheckActivity",
            "com.xiaomi.account.utils.DeviceInfoProvider",
            "com.xiaomi.account.utils.MiuiTelephonyManager",
            "com.xiaomi.phonenum.phone.PhoneInfo",
            "com.xiaomi.phonenum.phone.PhoneUtil",
            "miui.telephony.CloudTelephonyManager",
            "miui.browser.os.MiuiTelephonyManager"
    };

    private static final String[] IDENTIFIER_METHODS = {
            "getImei",
            "getImeiForSubscriber",
            "getImeiForSubscriberWithFeature",
            "getImeiForSlot",
            "getImeiForSubscription",
            "getImeiList",
            "getImeiListMd5",
            "getImeis",
            "getImei1",
            "getImei2",
            "getImei1Md5",
            "getImei2Md5",
            "getImeiMd5",
            "getImeiMd5LastLetter",
            "getIMEI",
            "getIMEIMD5",
            "getIMEIMd5",
            "getImeiInfo",
            "getImeiOrMeid",
            "getPrimaryImei",
            "getDeviceId",
            "getDeviceIdForPhone",
            "getDeviceIdForPhoneWithFeature",
            "getDeviceIdWithFeature",
            "getDeviceIdForSlot",
            "getDeviceIdForSubscription",
            "getDeviceIdList",
            "getDeviceIdQuietly",
            "getMiuiDeviceId",
            "getSmallDeviceId",
            "getMeid",
            "getMeidForSubscriber",
            "getMeidForSubscriberWithFeature",
            "getMeidForSlot",
            "getMeidForSubscription",
            "getMeidList"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if ("com.richard.imeiprivacy".equals(loadPackageParam.packageName)) {
            return;
        }
        // Boot-scoped property: a UI change is deliberately not applied to a
        // subset of already-running phone/system processes.
        if ("0".equals(FindDeviceSelfTest.property("sys.ipriv.enabled", "1"))) {
            return;
        }

        if ("com.xiaomi.finddevice".equals(loadPackageParam.packageName)) {
            FindDeviceNetworkGate.install(loadPackageParam.classLoader);
        }

        loadRuntimeIdentifiers();
        int hooked = 0;
        for (String className : TELEPHONY_CLASSES) {
            Class<?> target = XposedHelpers.findClassIfExists(
                    className, loadPackageParam.classLoader);
            if (target == null) {
                continue;
            }
            for (String methodName : IDENTIFIER_METHODS) {
                try {
                    hooked += HookRegistry.methods("telephony",
                            target, IDENTIFIER_REPLACEMENT, methodName);
                } catch (Throwable ignored) {
                    // OEM frameworks frequently omit or rename some overloads.
                }
            }
        }

        hookSystemProperties(loadPackageParam.classLoader);
        FindDevicePrivacyHook.install(loadPackageParam);
        if (hooked > 0) {
            XposedBridge.log(TAG + ": installed " + hooked + " new identifier hooks in "
                    + loadPackageParam.packageName);
        }
    }

    private static final XC_MethodHook IDENTIFIER_REPLACEMENT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!(param.method instanceof Method)) {
                return;
            }
            Method method = (Method) param.method;
            int slot = resolveSlot(param.thisObject, method.getName(), param.args);
            String lowerName = method.getName().toLowerCase();
            boolean meid = lowerName.contains("meid") && !lowerName.contains("imei");
            boolean md5 = lowerName.contains("md5");
            String first = meid ? FAKE_MEID[slot] : FAKE_IMEI[slot];
            if (method.getReturnType() == String.class) {
                String replacement = md5 ? md5(first) : first;
                if (lowerName.contains("lastletter")) {
                    replacement = replacement.substring(replacement.length() - 1);
                }
                param.setResult(replacement);
            } else if (List.class.isAssignableFrom(method.getReturnType())) {
                param.setResult(new ArrayList<>(Arrays.asList(
                        listValue(meid, md5, 0),
                        listValue(meid, md5, 1))));
            } else if (method.getReturnType() == String[].class) {
                param.setResult(new String[]{
                        listValue(meid, md5, 0),
                        listValue(meid, md5, 1)});
            } else if (method.getReturnType().isArray()
                    && "com.qti.extphone.QtiImeiInfo".equals(
                    method.getReturnType().getComponentType().getName())) {
                Object replacement = buildQtiImeiInfoArray(
                        method.getReturnType().getComponentType());
                if (replacement != null) {
                    param.setResult(replacement);
                }
            }
        }
    };

    private static String listValue(boolean meid, boolean md5, int slot) {
        String value = meid ? FAKE_MEID[slot] : FAKE_IMEI[slot];
        return md5 ? md5(value) : value;
    }

    static String fakeImei(int slot) {
        return FAKE_IMEI[slot == 1 ? 1 : 0];
    }

    static String fakeMeid(int slot) {
        return FAKE_MEID[slot == 1 ? 1 : 0];
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(32);
            for (byte item : digest) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static Object buildQtiImeiInfoArray(Class<?> componentType) {
        try {
            Field primaryField = componentType.getDeclaredField("IMEI_TYPE_PRIMARY");
            Field secondaryField = componentType.getDeclaredField("IMEI_TYPE_SECONDARY");
            primaryField.setAccessible(true);
            secondaryField.setAccessible(true);
            int primary = primaryField.getInt(null);
            int secondary = secondaryField.getInt(null);
            Constructor<?> constructor = componentType.getDeclaredConstructor(
                    int.class, String.class, int.class);
            constructor.setAccessible(true);
            Object result = Array.newInstance(componentType, 2);
            Array.set(result, 0, constructor.newInstance(0, FAKE_IMEI[0], primary));
            Array.set(result, 1, constructor.newInstance(1, FAKE_IMEI[1], secondary));
            return result;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": unable to build QTI IMEI result: " + throwable);
            return null;
        }
    }

    private static void loadRuntimeIdentifiers() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getDeclaredMethod("get", String.class, String.class);
            IdentifierProfile profile = IdentifierProfile.load((key, fallback) -> {
                try {
                    return (String) get.invoke(null, key, fallback);
                } catch (Throwable ignored) {
                    return fallback;
                }
            });
            FAKE_IMEI = profile.imei;
            FAKE_MEID = profile.meid;
        } catch (Throwable ignored) {
            // Some app domains cannot read this property. The valid fallback
            // still prevents the real identifier from being returned.
        }
    }

    private static void hookSystemProperties(ClassLoader classLoader) {
        Class<?> systemProperties = XposedHelpers.findClassIfExists(
                "android.os.SystemProperties", classLoader);
        if (systemProperties == null) {
            return;
        }
        try {
            HookRegistry.methods("imei-properties", systemProperties, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String replacement = replacementForProperty((String) param.args[0]);
                    if (replacement != null) {
                        param.setResult(replacement);
                    }
                }
            }, "get");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": SystemProperties hook failed: " + throwable);
        }
    }

    private static String replacementForProperty(String key) {
        switch (key) {
            case "ro.vendor.oem.imei2":
            case "persist.vendor.radio.imei2":
            case "ro.ril.oem.imei2":
            case "persist.radio.imei2":
            case "ro.ril.miui.imei1":
                return FAKE_IMEI[1];
            case "ro.vendor.oem.imei":
            case "ro.vendor.oem.imei1":
            case "persist.vendor.radio.imei":
            case "persist.vendor.radio.imei1":
            case "ro.ril.oem.imei":
            case "ro.ril.oem.imei1":
            case "persist.radio.imei":
            case "persist.radio.imei1":
            case "ro.ril.miui.imei0":
                return FAKE_IMEI[0];
            default:
                return null;
        }
    }

    private static int resolveSlot(Object receiver, String methodName, Object[] args) {
        String lowerName = methodName.toLowerCase();
        if (lowerName.contains("imei2") || lowerName.contains("meid2")) {
            return 1;
        }
        if (lowerName.contains("imei1") || lowerName.contains("meid1")) {
            return 0;
        }
        int candidate = firstInteger(args);
        if (candidate >= 0) {
            if (methodName.contains("Subscriber")) {
                try {
                    int slot = slotIndexForSubscription(candidate);
                    if (slot == 0 || slot == 1) {
                        return slot;
                    }
                } catch (Throwable ignored) {
                    // Fall through: some vendor frameworks use a phone ID here.
                }
            }
            if (candidate == 0 || candidate == 1) {
                return candidate;
            }
        }

        Integer phoneId = reflectSlot(receiver);
        return phoneId != null && phoneId == 1 ? 1 : 0;
    }

    private static int firstInteger(Object[] args) {
        if (args == null) {
            return -1;
        }
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        return -1;
    }

    private static int slotIndexForSubscription(int subscription) {
        if (Build.VERSION.SDK_INT >= 29) return SubscriptionManager.getSlotIndex(subscription);
        try {
            Method method = SubscriptionManager.class.getDeclaredMethod("getSlotIndex", int.class);
            method.setAccessible(true);
            Object value = method.invoke(null, subscription);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Integer reflectSlot(Object receiver) {
        if (receiver == null) {
            return null;
        }
        String[] accessors = {"getSlotId", "getPhoneId", "getSlotIndex", "getSubscriptionId"};
        for (String accessor : accessors) {
            Class<?> type = receiver.getClass();
            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(accessor);
                    method.setAccessible(true);
                    Object value = method.invoke(receiver);
                    if (value instanceof Integer) {
                        int candidate = (Integer) value;
                        if (accessor.equals("getSubscriptionId")) {
                            int slot = slotIndexForSubscription(candidate);
                            return slot >= 0 ? slot : null;
                        }
                        return candidate;
                    }
                    break;
                } catch (NoSuchMethodException ignored) {
                    type = type.getSuperclass();
                } catch (Throwable ignored) {
                    break;
                }
            }
        }
        return null;
    }
}
