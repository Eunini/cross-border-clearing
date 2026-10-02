package io.github.eunini.clearing.gateway.validation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Supported currencies and their ISO 4217 minor-unit exponents. */
public final class CurrencyRules {

    private static final Map<String, Integer> EXPONENTS = Map.ofEntries(
            Map.entry("USD", 2), Map.entry("EUR", 2), Map.entry("GBP", 2), Map.entry("JPY", 0),
            Map.entry("CHF", 2), Map.entry("CAD", 2), Map.entry("AUD", 2), Map.entry("SGD", 2),
            Map.entry("HKD", 2), Map.entry("INR", 2), Map.entry("BRL", 2), Map.entry("MXN", 2),
            Map.entry("CNY", 2));

    private CurrencyRules() {}

    public static boolean isSupported(String ccy) {
        return EXPONENTS.containsKey(ccy);
    }

    public static Set<String> supported() {
        return EXPONENTS.keySet();
    }

    public static int exponent(String ccy) {
        Integer e = EXPONENTS.get(ccy);
        if (e == null) {
            throw new IllegalArgumentException("Unsupported currency " + ccy);
        }
        return e;
    }

    /**
     * Converts a major-unit amount to minor units if it is positive and has no
     * more decimals than the currency allows (e.g. "1500.5" JPY is invalid).
     */
    public static Optional<Long> toMinor(BigDecimal amount, String ccy) {
        if (amount == null || amount.signum() <= 0) {
            return Optional.empty();
        }
        int exp = exponent(ccy);
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > exp) {
            return Optional.empty();
        }
        try {
            return Optional.of(stripped.movePointRight(exp).longValueExact());
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }

    public static BigDecimal toMajor(long minor, String ccy) {
        return BigDecimal.valueOf(minor, exponent(ccy));
    }
}
