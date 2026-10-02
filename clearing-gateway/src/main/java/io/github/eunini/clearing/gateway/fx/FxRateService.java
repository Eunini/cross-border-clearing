package io.github.eunini.clearing.gateway.fx;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.validation.CurrencyRules;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Simulated FX desk. Mid rates (units per settlement unit) follow a seeded
 * geometric random walk; each currency has a fixed bid/ask spread. HKD is
 * kept inside its 7.75-7.85 convertibility band against USD, mirroring the
 * linked exchange rate system.
 *
 * <p>The base rates are round, plausible magnitudes chosen for the
 * simulation. They are not market data.
 */
@Service
public class FxRateService {

    /** Simulated base mid rates per 1 USD. */
    static final Map<String, BigDecimal> BASE_MID = orderedMap(
            "USD", "1", "EUR", "0.92", "GBP", "0.78", "JPY", "148", "CHF", "0.88", "CAD", "1.37",
            "AUD", "1.52", "SGD", "1.34", "HKD", "7.80", "INR", "84.00", "BRL", "5.40", "MXN", "18.50",
            "CNY", "7.20");

    /** Full bid/ask spread in basis points. */
    static final Map<String, Integer> SPREAD_BPS = Map.ofEntries(
            Map.entry("USD", 0), Map.entry("EUR", 5), Map.entry("GBP", 6), Map.entry("JPY", 6),
            Map.entry("CHF", 8), Map.entry("CAD", 8), Map.entry("AUD", 8), Map.entry("SGD", 10),
            Map.entry("HKD", 6), Map.entry("INR", 30), Map.entry("BRL", 40), Map.entry("MXN", 25),
            Map.entry("CNY", 20));

    private static final BigDecimal HKD_LOW = new BigDecimal("7.75");
    private static final BigDecimal HKD_HIGH = new BigDecimal("7.85");

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal BPS_TO_HALF_FRACTION = BigDecimal.valueOf(20_000); // bps -> fraction, halved

    private final ClearingProperties props;
    private final Clock clock;
    private final Random random;
    private volatile Map<String, BigDecimal> mid;

    public FxRateService(ClearingProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.random = new Random(props.fx().seed());
        this.mid = Map.copyOf(BASE_MID);
    }

    private static Map<String, BigDecimal> orderedMap(String... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], new BigDecimal(kv[i + 1]));
        }
        return m;
    }

    /** One step of the random walk. */
    public synchronized void tick() {
        double sigma = props.fx().volatilityBps() / 10_000.0;
        Map<String, BigDecimal> next = new LinkedHashMap<>();
        for (var e : mid.entrySet()) {
            String ccy = e.getKey();
            if (ccy.equals(props.settlementCurrency())) {
                next.put(ccy, BigDecimal.ONE);
                continue;
            }
            BigDecimal moved = move(e.getValue(), sigma);
            if (ccy.equals("HKD")) {
                moved = moved.max(HKD_LOW).min(HKD_HIGH);
            }
            next.put(ccy, moved);
        }
        mid = Map.copyOf(next);
    }

    private BigDecimal move(BigDecimal rate, double sigma) {
        double factor = Math.exp(sigma * random.nextGaussian());
        return rate.multiply(BigDecimal.valueOf(factor), MC).setScale(6, RoundingMode.HALF_EVEN);
    }

    public Map<String, BigDecimal> midRates() {
        return mid;
    }

    public int spreadBps(String ccy) {
        return SPREAD_BPS.getOrDefault(ccy, 0);
    }

    /**
     * Quotes a conversion of {@code sourceAmountMinor} from {@code source} into
     * the settlement currency and then into {@code target}. Each leg is rounded
     * half-even to the minor unit of its currency.
     */
    public Optional<FxQuote> quote(String source, String target, long sourceAmountMinor) {
        Map<String, BigDecimal> rates = mid;
        BigDecimal srcMid = rates.get(source);
        BigDecimal tgtMid = rates.get(target);
        if (srcMid == null || tgtMid == null) {
            return Optional.empty();
        }
        String settlement = props.settlementCurrency();
        // Participant sells source currency: pays more source units per settlement unit.
        BigDecimal srcRate = srcMid.multiply(BigDecimal.ONE.add(halfSpread(source)), MC);
        // Participant's customer buys target currency: receives fewer target units per settlement unit.
        BigDecimal tgtRate = tgtMid.multiply(BigDecimal.ONE.subtract(halfSpread(target)), MC);

        BigDecimal sourceMajor = CurrencyRules.toMajor(sourceAmountMinor, source);
        BigDecimal settlementMajor = sourceMajor.divide(srcRate, MC)
                .setScale(CurrencyRules.exponent(settlement), RoundingMode.HALF_EVEN);
        BigDecimal targetMajor = settlementMajor.multiply(tgtRate, MC)
                .setScale(CurrencyRules.exponent(target), RoundingMode.HALF_EVEN);
        Instant now = clock.instant();
        return Optional.of(new FxQuote(UUID.randomUUID(), source, target, settlement, sourceAmountMinor,
                settlementMajor.movePointRight(CurrencyRules.exponent(settlement)).longValueExact(),
                targetMajor.movePointRight(CurrencyRules.exponent(target)).longValueExact(),
                srcRate.setScale(8, RoundingMode.HALF_EVEN), tgtRate.setScale(8, RoundingMode.HALF_EVEN),
                now, now.plus(props.quoteTtl())));
    }

    private BigDecimal halfSpread(String ccy) {
        return BigDecimal.valueOf(spreadBps(ccy)).divide(BPS_TO_HALF_FRACTION, MC);
    }
}
