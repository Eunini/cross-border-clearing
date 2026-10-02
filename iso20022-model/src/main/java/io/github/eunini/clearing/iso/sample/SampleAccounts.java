package io.github.eunini.clearing.iso.sample;

import java.math.BigInteger;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Generates syntactically valid (but fictional) account identifiers for the
 * simulated participants, independently of the gateway's validators so that
 * tests exercise them against a separate implementation.
 */
public final class SampleAccounts {

    /** BBAN lengths (IBAN length minus 4) for IBAN countries in the simulation. */
    private static final Map<String, Integer> BBAN_LENGTH = Map.of(
            "GB", 18, "DE", 18, "FR", 23, "NL", 14, "CH", 17, "BR", 25);

    private static final Map<String, Integer> DOMESTIC_LENGTH = Map.of(
            "US", 12, "CA", 10, "IN", 14, "SG", 10, "HK", 12, "JP", 7, "AU", 9, "CN", 19);

    private SampleAccounts() {}

    public static boolean usesIban(String country) {
        return BBAN_LENGTH.containsKey(country);
    }

    /** A valid account for a participant in {@code country} with BIC {@code bic}. */
    public static Pacs008Writer.Account forParticipant(String country, String bic, RandomGenerator rng) {
        if (usesIban(country)) {
            return Pacs008Writer.Account.iban(iban(country, bic, rng));
        }
        if (country.equals("MX")) {
            return Pacs008Writer.Account.other(clabe("9" + bic.substring(2, 4), rng));
        }
        Integer len = DOMESTIC_LENGTH.get(country);
        if (len == null) {
            throw new IllegalArgumentException("No account format for " + country);
        }
        return Pacs008Writer.Account.other(digits(len, rng));
    }

    /** IBAN whose BBAN starts with the first four characters of the BIC (bank code), then digits. */
    public static String iban(String country, String bic, RandomGenerator rng) {
        int len = BBAN_LENGTH.get(country);
        String bban = bic.substring(0, 4) + digits(len - 4, rng);
        StringBuilder numeric = new StringBuilder();
        for (char c : (bban + country + "00").toCharArray()) {
            numeric.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        int check = 98 - new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
        return country + String.format("%02d", check) + bban;
    }

    /** 18-digit CLABE: 3-digit bank code, 14 digits, check digit with weights 3, 7, 1. */
    public static String clabe(String bankCode, RandomGenerator rng) {
        String body = bankCode + digits(14, rng);
        int[] w = {3, 7, 1};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += ((body.charAt(i) - '0') * w[i % 3]) % 10;
        }
        return body + ((10 - sum % 10) % 10);
    }

    public static String digits(int n, RandomGenerator rng) {
        StringBuilder sb = new StringBuilder(n);
        sb.append(1 + rng.nextInt(9));
        for (int i = 1; i < n; i++) {
            sb.append(rng.nextInt(10));
        }
        return sb.toString();
    }
}
