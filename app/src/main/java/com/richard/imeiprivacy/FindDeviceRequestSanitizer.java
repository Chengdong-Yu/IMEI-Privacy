package com.richard.imeiprivacy;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Copies request data: immutable arguments and account credentials stay intact. */
final class FindDeviceRequestSanitizer {
    private final Map<String, String> replacements;

    FindDeviceRequestSanitizer(Map<String, String> replacements) {
        this.replacements = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            this.replacements.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
    }

    String replacement(String key) {
        return replacements.get(key.toLowerCase(Locale.ROOT));
    }

    Map<Object, Object> copyMap(Map<?, ?> source) {
        return copyMap(source, 0);
    }

    private Map<Object, Object> copyMap(Map<?, ?> source, int depth) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            String replacement = key instanceof String
                    ? replacements.get(((String) key).toLowerCase(Locale.ROOT)) : null;
            if (replacement != null) {
                if (value instanceof List) {
                    value = "[]".equals(replacement)
                            ? Collections.emptyList() : Collections.singletonList(replacement);
                } else if (value instanceof Map && "{}".equals(replacement)) {
                    value = Collections.emptyMap();
                } else {
                    value = replacement;
                }
            } else if (depth < 8 && value instanceof Map) {
                value = copyMap((Map<?, ?>) value, depth + 1);
            } else if (depth < 8 && value instanceof List) {
                List<Object> copy = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    copy.add(item instanceof Map ? copyMap((Map<?, ?>) item, depth + 1) : item);
                }
                value = copy;
            }
            result.put(key, value);
        }
        return result;
    }

    String url(String url) {
        int query = url.indexOf('?');
        if (query < 0) return url;
        int fragment = url.indexOf('#', query);
        int end = fragment < 0 ? url.length() : fragment;
        return url.substring(0, query + 1) + form(url.substring(query + 1, end))
                + url.substring(end);
    }

    String form(String form) {
        String[] parts = form.split("&", -1);
        for (int index = 0; index < parts.length; index++) {
            int equals = parts[index].indexOf('=');
            String rawKey = equals < 0 ? parts[index] : parts[index].substring(0, equals);
            try {
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                        .toLowerCase(Locale.ROOT);
                String replacement = replacements.get(key);
                if (replacement != null) {
                    parts[index] = rawKey + "="
                            + URLEncoder.encode(replacement, StandardCharsets.UTF_8.name());
                }
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new AssertionError(impossible);
            } catch (IllegalArgumentException ignored) {
                // Do not normalize unrelated, malformed authentication/query fields.
            }
        }
        return String.join("&", parts);
    }
}
