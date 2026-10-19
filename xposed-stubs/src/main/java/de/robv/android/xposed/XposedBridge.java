package de.robv.android.xposed;

import java.util.Set;
import java.lang.reflect.Member;

public final class XposedBridge {
    private XposedBridge() {}
    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) { return null; }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) { return null; }
    public static void log(String text) {}
}
