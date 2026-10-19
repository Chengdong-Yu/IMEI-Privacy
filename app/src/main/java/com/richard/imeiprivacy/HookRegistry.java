package com.richard.imeiprivacy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Process-local registration. Shared-UID package callbacks must not stack hooks. */
final class HookRegistry {
    private static final Map<String, Set<Member>> HOOKS = new HashMap<>();

    private HookRegistry() {}

    static synchronized boolean contains(String group, Member member) {
        Set<Member> installed = HOOKS.get(group);
        return installed != null && installed.contains(member);
    }

    static synchronized int hook(String group, Member member, XC_MethodHook callback) {
        Set<Member> installed = HOOKS.computeIfAbsent(group, key -> new HashSet<>());
        if (installed.contains(member)) {
            return 0;
        }
        if (member instanceof Method && Modifier.isAbstract(member.getModifiers())) {
            return 0;
        }
        try {
            if (XposedBridge.hookMethod(member, callback) != null) {
                installed.add(member);
                return 1;
            }
        } catch (Throwable error) {
            // Never log arguments, profile values or exception messages with identifiers.
            XposedBridge.log("ImeiPrivacy: hook failed " + member.getDeclaringClass().getName()
                    + "." + member.getName() + " (" + error.getClass().getSimpleName() + ")");
        }
        return 0;
    }

    static int methods(String group, Class<?> type, XC_MethodHook callback, String... names) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            for (String name : names) {
                if (name.equals(method.getName())) {
                    count += hook(group, method, callback);
                    break;
                }
            }
        }
        return count;
    }

    static int constructors(String group, Class<?> type, XC_MethodHook callback) {
        int count = 0;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            count += hook(group, constructor, callback);
        }
        return count;
    }
}
