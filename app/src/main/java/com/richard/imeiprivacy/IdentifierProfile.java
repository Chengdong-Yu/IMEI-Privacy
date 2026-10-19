package com.richard.imeiprivacy;

import java.util.function.BiFunction;

/** Only module-owned properties are trusted as a pseudonym source. */
final class IdentifierProfile {
    final String[] imei;
    final String[] meid;

    private IdentifierProfile(String[] imei, String[] meid) {
        this.imei = imei;
        this.meid = meid;
    }

    static IdentifierProfile load(BiFunction<String, String, String> get) {
        String[] imei = {"358240051234565", "358240056543218"};
        String[] meid = {"A1000000000001", "A1000000000002"};
        for (int slot = 0; slot < 2; slot++) {
            String candidate = get.apply("persist.sys.ipriv.imei" + slot, "");
            if (isValidImei(candidate)) {
                imei[slot] = candidate;
            }
            candidate = get.apply("persist.sys.ipriv.meid" + slot, "");
            if (candidate != null && candidate.matches("[0-9a-fA-F]{14}")) {
                meid[slot] = candidate.toUpperCase(java.util.Locale.ROOT);
            }
        }
        return new IdentifierProfile(imei, meid);
    }

    static boolean isValidImei(String value) {
        if (value == null || !value.matches("[0-9]{15}")) {
            return false;
        }
        int sum = 0;
        for (int index = 0; index < value.length(); index++) {
            int digit = value.charAt(index) - '0';
            if ((index & 1) == 1) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }
}
