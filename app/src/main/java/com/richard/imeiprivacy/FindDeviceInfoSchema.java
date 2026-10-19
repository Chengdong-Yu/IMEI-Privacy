package com.richard.imeiprivacy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.HashSet;

/** Validate the semantically discovered mapping against the loaded class. */
final class FindDeviceInfoSchema {
    private FindDeviceInfoSchema() {}

    static boolean matches(Class<?> type, Map<DexHookPlan.Role, String> fields) {
        try {
            if (fields.size() != DexHookPlan.Role.values().length
                    || new HashSet<>(fields.values()).size() != fields.size()) return false;
            for (String name : fields.values()) {
                Field field = type.getDeclaredField(name);
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
