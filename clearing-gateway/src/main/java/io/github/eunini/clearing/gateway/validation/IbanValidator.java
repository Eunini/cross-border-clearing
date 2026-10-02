package io.github.eunini.clearing.gateway.validation;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * ISO 13616 IBAN check: structure, country-specific length (for the countries
 * in this network that use IBANs) and the MOD 97-10 check digits (ISO 7064).
 */
public final class IbanValidator {

    private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");

    /** IBAN lengths for participant countries that use IBANs in this simulation. */
    static final Map<String, Integer> LENGTHS = Map.of(
            "GB", 22,
            "DE", 22,
            "FR", 27,
            "NL", 18,
            "CH", 21,
            "BR", 29);

    private IbanValidator() {}

    public static boolean isValid(String iban) {
        if (iban == null || !SHAPE.matcher(iban).matches()) {
            return false;
        }
        Integer expected = LENGTHS.get(iban.substring(0, 2));
        if (expected != null && iban.length() != expected) {
            return false;
        }
        return mod97(iban.substring(4) + iban.substring(0, 4)) == 1;
    }

    /** Computes the two check digits for a country code and BBAN. */
    public static String checkDigits(String country, String bban) {
        int r = mod97(bban + country + "00");
        return String.format("%02d", 98 - r);
    }

    static int mod97(String s) {
        int r = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                r = (r * 10 + (c - '0')) % 97;
            } else {
                int v = c - 'A' + 10;
                r = (r * 100 + v) % 97;
            }
        }
        return r;
    }
}
