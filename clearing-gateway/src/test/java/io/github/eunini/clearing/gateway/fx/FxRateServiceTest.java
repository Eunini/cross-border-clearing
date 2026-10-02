package io.github.eunini.clearing.gateway.fx;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FxRateServiceTest {

    static ClearingProperties props(double volBps) {
        return new ClearingProperties("USD", Duration.ofSeconds(30), 500, 1 << 20, 100_000_000L,
                new ClearingProperties.Scheduling(false, Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(200), 100),
                new ClearingProperties.Engine("http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1),
                new ClearingProperties.Fx(42, Duration.ofSeconds(1), volBps));
    }

    final Clock clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void usdToUsdIsExactAndExpiresAfterTtl() {
        FxQuote q = new FxRateService(props(2), clock).quote("USD", "USD", 12_345).orElseThrow();
        assertThat(q.settlementAmount()).isEqualTo(12_345);
        assertThat(q.targetAmount()).isEqualTo(12_345);
        assertThat(q.expiresAt()).isEqualTo(clock.instant().plusSeconds(30));
    }

    @Test
    void spreadsAreChargedOnBothLegsAndRoundedPerCurrency() {
        FxRateService fx = new FxRateService(props(2), clock);
        // 100.00 EUR at mid 0.92 widened by 2.5 bps -> 108.67 USD; to JPY at 148 narrowed by 3 bps.
        FxQuote q = fx.quote("EUR", "JPY", 10_000).orElseThrow();
        BigDecimal usd = new BigDecimal("100").divide(new BigDecimal("0.92").multiply(new BigDecimal("1.00025")),
                java.math.MathContext.DECIMAL64).setScale(2, java.math.RoundingMode.HALF_EVEN);
        assertThat(q.settlementAmount()).isEqualTo(usd.movePointRight(2).longValueExact());
        long jpy = usd.multiply(new BigDecimal("148").multiply(new BigDecimal("0.9997")))
                .setScale(0, java.math.RoundingMode.HALF_EVEN).longValueExact();
        assertThat(q.targetAmount()).isEqualTo(jpy); // JPY has no minor unit
        // The customer always receives less than at mid.
        assertThat(q.targetAmount()).isLessThan(Math.round(100 / 0.92 * 148));
    }

    @Test
    void randomWalkIsSeededAndKeepsHkdInsideItsBand() {
        FxRateService a = new FxRateService(props(500), clock);
        FxRateService b = new FxRateService(props(500), clock);
        for (int i = 0; i < 500; i++) {
            a.tick();
            b.tick();
            BigDecimal hkd = a.midRates().get("HKD");
            assertThat(hkd).isBetween(new BigDecimal("7.75"), new BigDecimal("7.85"));
        }
        assertThat(a.midRates()).isEqualTo(b.midRates());
        assertThat(a.midRates().get("USD")).isEqualByComparingTo("1");
    }

    @Test
    void unknownCurrencyHasNoQuote() {
        assertThat(new FxRateService(props(2), clock).quote("USD", "XYZ", 100)).isEmpty();
    }
}
