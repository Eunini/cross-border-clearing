package io.github.eunini.clearing.gateway.validation;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ISO 9362:2014 BIC structure: 4-character party prefix (letters or digits),
 * 2-letter ISO 3166 country code, 2-character location code, optional
 * 3-character branch code.
 */
public final class BicValidator {

    private static final Pattern BIC = Pattern.compile("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

    private BicValidator() {}

    public static boolean isValid(String bic) {
        return bic != null && BIC.matcher(bic).matches() && COUNTRIES.contains(bic.substring(4, 6));
    }

    public static String country(String bic) {
        return bic.substring(4, 6);
    }

    /** BIC8 form (branch code dropped; "XXX" denotes the head office). */
    public static String bic8(String bic) {
        return bic.length() == 11 ? bic.substring(0, 8) : bic;
    }
}
