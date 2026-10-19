package com.richard.imeiprivacy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Resolve by declared signature, not runtime values (headers may be null). */
final class FindDeviceTransportLayout {
    final int url;
    final int headers;
    final int body;

    private FindDeviceTransportLayout(int url, int headers, int body) {
        this.url = url;
        this.headers = headers;
        this.body = body;
    }

    static FindDeviceTransportLayout of(Method method) {
        List<String> types = new ArrayList<>();
        for (Class<?> type : method.getParameterTypes()) {
            types.add(type.isArray() ? type.getName().replace('.', '/')
                    : type.isPrimitive() ? "primitive" : "L" + type.getName().replace('.', '/') + ";");
        }
        return of(types);
    }

    static boolean sender(List<String> types) {
        return types.size() >= 4 && "Ljava/lang/String;".equals(types.get(0))
                && "Ljava/lang/String;".equals(types.get(1)) && map(types.get(2))
                && "Ljava/lang/Object;".equals(types.get(3));
    }

    static boolean recognizes(List<String> types) { return of(types) != null; }

    private static boolean map(String type) {
        return "Ljava/util/Map;".equals(type) || "Ljava/util/HashMap;".equals(type);
    }

    // The semantic scanner separately verifies HTTP code and delegation. A
    // matching signature alone is NOT sufficient to install a transport hook.
    private static FindDeviceTransportLayout of(List<String> types) {
        if (sender(types)) {
            return new FindDeviceTransportLayout(1, 2, 3);
        }
        if (types.isEmpty() || !"Ljava/lang/String;".equals(types.get(0))) return null;
        int headers = types.size() > 1 && map(types.get(1)) ? 1 : -1;
        int body = headers < 0 ? 1 : 2;
        boolean post = types.size() > body && "Ljava/lang/Object;".equals(types.get(body));
        return new FindDeviceTransportLayout(0, headers, post ? body : -1);
    }
}
