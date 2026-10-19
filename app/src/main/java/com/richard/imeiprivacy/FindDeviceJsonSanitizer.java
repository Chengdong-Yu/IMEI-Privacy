package com.richard.imeiprivacy;

import java.util.Iterator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class FindDeviceJsonSanitizer {
    private FindDeviceJsonSanitizer() {}

    static Object copy(Object input, FindDeviceRequestSanitizer policy) throws JSONException {
        return copy(input, policy, 0);
    }

    private static Object copy(Object input, FindDeviceRequestSanitizer policy, int depth)
            throws JSONException {
        if (depth > 32) throw new JSONException("Privacy request nesting limit");
        if (input instanceof JSONObject) {
            JSONObject source = (JSONObject) input;
            JSONObject result = new JSONObject();
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String replacement = policy.replacement(key);
                Object original = source.get(key);
                Object value = replacement;
                if (replacement == null) {
                    value = copy(original, policy, depth + 1);
                } else if (original instanceof JSONArray && "[]".equals(replacement)) {
                    value = new JSONArray();
                } else if (original instanceof JSONObject && "{}".equals(replacement)) {
                    value = new JSONObject();
                }
                result.put(key, value);
            }
            return result;
        }
        if (input instanceof JSONArray) {
            JSONArray source = (JSONArray) input;
            JSONArray result = new JSONArray();
            for (int index = 0; index < source.length(); index++) {
                result.put(copy(source.get(index), policy, depth + 1));
            }
            return result;
        }
        return input;
    }
}
