package com.richard.imeiprivacy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Immediate per-process gate; the root companion covers native sockets per UID. */
final class FindDeviceNetworkGate {
    static final String DENIED = "IPRIV_GUARD_BLOCKED";

    private FindDeviceNetworkGate() {}

    static int install(ClassLoader loader) {
        int count = 0;
        count += hook(loader, "java.net.Socket", "connect");
        count += hook(loader, "java.net.DatagramSocket", "send", "connect");
        count += hook(loader, "java.net.URL", "openConnection");
        count += hook(loader, "java.net.InetAddress", "getByName", "getAllByName");
        count += hook(loader, "android.net.Network", "openConnection");
        count += hook(loader, "libcore.io.IoBridge", "connect", "sendto");
        count += hook(loader, "java.net.SocketOutputStream", "socketWrite", "socketWrite0");
        count += hook(loader, "com.android.org.conscrypt.ConscryptFileDescriptorSocket", "startHandshake");
        count += hook(loader, "com.android.org.conscrypt.ConscryptEngineSocket", "startHandshake");
        return count;
    }

    private static int hook(ClassLoader loader, String name, String... methods) {
        try {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type == null) return 0;
            return HookRegistry.methods("finddevice-network-gate", type, new XC_MethodHook(100) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    boolean deny;
                    try { deny = HookProbe.forceNetworkDenial() || !FindDeviceSelfTest.permitsNetwork(); }
                    catch (Throwable ignored) { deny = true; }
                    if (deny) {
                        param.setThrowable("java.net.InetAddress".equals(name)
                                ? new UnknownHostException(DENIED) : new IOException(DENIED));
                    }
                }
            }, methods);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static void verifyDispatch() throws Throwable {
        Method connect = Socket.class.getDeclaredMethod("connect", SocketAddress.class, int.class);
        // Even with a missing gate, the stopper throws before Socket.connect's
        // original implementation. No socket address, DNS or packet is used.
        try (Socket receiver = new Socket()) {
            HookProbe.Result result = HookProbe.invoke(connect, receiver, new Object[]{null, 1}, null,
                    new IOException("IPRIV_PROBE_GATE_MISSING"), java.util.Collections.emptyMap(), true);
            if (result.error == null || !DENIED.equals(result.error.getMessage())) {
                throw new IllegalStateException("NETWORK_GATE_NOT_EFFECTIVE");
            }
        }
    }
}
