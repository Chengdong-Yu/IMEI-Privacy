package com.richard.imeiprivacy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Exercise the actual Xposed dispatch without executing application originals.
 * A low-priority stopper prevents network/storage/TEE/binding side effects even
 * if the privacy callback is absent or throws. The observer runs after it.
 */
final class HookProbe {
    private static final ThreadLocal<Call> ACTIVE = new ThreadLocal<>();

    static final class Result {
        Object value;
        Object receiver;
        Object[] args;
        Throwable error;
        boolean observed;
    }

    private static final class Call {
        final Member target;
        final Object seed;
        final Throwable fallback;
        final Map<String, String> fields;
        final boolean forceNetworkDenial;
        final Result result = new Result();

        Call(Member target, Object seed, Throwable fallback, Map<String, String> fields, boolean deny) {
            this.target = target;
            this.seed = seed;
            this.fallback = fallback;
            this.fields = fields;
            this.forceNetworkDenial = deny;
        }
    }

    private HookProbe() {}

    static boolean active(Member member) {
        Call call = ACTIVE.get();
        return call != null && call.target.equals(member);
    }

    static boolean forceNetworkDenial() {
        Call call = ACTIVE.get();
        return call != null && call.forceNetworkDenial;
    }

    static Result invoke(Member target, Object receiver, Object[] args, Object seed) throws Throwable {
        return invoke(target, receiver, args, seed, null, Collections.emptyMap(), false);
    }

    static Result invoke(Member target, Object receiver, Object[] args, Object seed,
            Throwable fallback, Map<String, String> fields, boolean deny) throws Throwable {
        installCapture(target);
        if (!HookRegistry.contains("probe-observer", target) || !HookRegistry.contains("probe-stopper", target)) {
            throw new IllegalStateException("PROBE_CAPTURE_UNAVAILABLE");
        }
        if (ACTIVE.get() != null) throw new IllegalStateException("NESTED_PROBE");
        Call call = new Call(target, seed, fallback, fields, deny);
        ACTIVE.set(call);
        try {
            if (target instanceof Method) {
                Method method = (Method) target;
                method.setAccessible(true);
                method.invoke(receiver, args);
            } else {
                Constructor<?> constructor = (Constructor<?>) target;
                constructor.setAccessible(true);
                constructor.newInstance(args);
            }
        } catch (InvocationTargetException error) {
            if (!call.result.observed) throw error;
        } finally {
            ACTIVE.remove();
        }
        if (!call.result.observed) throw new IllegalStateException("PROBE_NOT_DISPATCHED");
        return call.result;
    }

    private static void installCapture(Member target) {
        HookRegistry.hook("probe-observer", target, new XC_MethodHook(10_000) {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Call call = ACTIVE.get();
                if (call == null || !call.target.equals(param.method) || param.thisObject == null) return;
                for (Map.Entry<String, String> field : call.fields.entrySet()) {
                    java.lang.reflect.Field targetField = param.thisObject.getClass().getDeclaredField(field.getKey());
                    targetField.setAccessible(true);
                    targetField.set(param.thisObject, field.getValue());
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Call call = ACTIVE.get();
                if (call == null || !call.target.equals(param.method)) return;
                call.result.value = param.getResult();
                call.result.error = param.getThrowable();
                call.result.receiver = param.thisObject;
                call.result.args = param.args.clone();
                call.result.observed = true;
            }
        });
        HookRegistry.hook("probe-stopper", target, new XC_MethodHook(-10_000) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Call call = ACTIVE.get();
                if (call == null || !call.target.equals(param.method)) return;
                if (call.fallback != null) param.setThrowable(call.fallback);
                else param.setResult(call.seed);
            }
        });
    }

    static Object allocate(Class<?> type) throws ReflectiveOperationException {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
    }
}
