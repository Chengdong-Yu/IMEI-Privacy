package com.richard.imeiprivacy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Discovery output contains symbols only, never device IDs, tokens or APK versions. */
final class DexHookPlan {
    enum Role { DEVICE_ID, CORE_ID, SERIAL, MAC, MODEL }

    static final class MethodId {
        final String owner;
        final String name;
        final List<String> parameters;
        final String result;

        MethodId(String owner, String name, List<? extends CharSequence> parameters, String result) {
            this.owner = owner;
            this.name = name;
            this.parameters = new ArrayList<>();
            for (CharSequence parameter : parameters) this.parameters.add(parameter.toString());
            this.result = result;
        }

        Method resolve(ClassLoader loader) throws ReflectiveOperationException {
            Class<?>[] arguments = new Class<?>[parameters.size()];
            for (int index = 0; index < arguments.length; index++) {
                arguments[index] = type(parameters.get(index), loader);
            }
            Method method = type(owner, loader).getDeclaredMethod(name, arguments);
            if (method.getReturnType() != type(result, loader)) throw new NoSuchMethodException(name);
            return method;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof MethodId)) return false;
            MethodId value = (MethodId) other;
            return owner.equals(value.owner) && name.equals(value.name)
                    && parameters.equals(value.parameters) && result.equals(value.result);
        }

        @Override public int hashCode() { return Objects.hash(owner, name, parameters, result); }
    }

    static final class Info {
        final String owner;
        final EnumMap<Role, String> fields;
        final Set<MethodId> factories = new LinkedHashSet<>();
        final EnumMap<Role, Set<MethodId>> readers = new EnumMap<>(Role.class);

        Info(String owner, EnumMap<Role, String> fields) {
            this.owner = owner;
            this.fields = fields;
        }
    }

    final List<Info> infos = new ArrayList<>();
    final Set<MethodId> credentials = new LinkedHashSet<>();
    final Set<MethodId> fidCaches = new LinkedHashSet<>();
    final Set<MethodId> cloudProviders = new LinkedHashSet<>();
    final Set<MethodId> requests = new LinkedHashSet<>();
    final Set<MethodId> transports = new LinkedHashSet<>();
    int dexFiles;
    int classes;
    int rejected;

    static Class<?> type(String descriptor, ClassLoader loader) throws ClassNotFoundException {
        switch (descriptor) {
            case "V": return void.class;
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "S": return short.class;
            case "C": return char.class;
            case "I": return int.class;
            case "J": return long.class;
            case "F": return float.class;
            case "D": return double.class;
            default:
                String name = descriptor.startsWith("L")
                        ? descriptor.substring(1, descriptor.length() - 1) : descriptor;
                return Class.forName(name.replace('/', '.'), false, loader);
        }
    }
}
