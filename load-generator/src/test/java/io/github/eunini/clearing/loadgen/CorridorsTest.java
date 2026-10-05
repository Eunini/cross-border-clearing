package io.github.eunini.clearing.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class CorridorsTest {

    final List<String> countries = List.of("US", "GB", "DE", "FR", "NL", "CH", "IN", "MX", "CA", "BR", "SG", "HK",
            "JP", "AU", "CN");

    @Test
    void neverProducesDomesticPairsAndFollowsTheSkew() {
        Corridors c = new Corridors(Corridors.DEFAULT, countries);
        SplittableRandom rng = new SplittableRandom(1);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            String[] p = c.pick(rng);
            assertThat(p[0]).isNotEqualTo(p[1]);
            assertThat(countries).contains(p[0], p[1]);
            counts.merge(p[0] + ">" + p[1], 1, Integer::sum);
        }
        // Remittance corridor is strongly one-directional.
        assertThat(counts.get("US>MX")).isGreaterThan(2 * counts.get("MX>US"));
        // A named corridor carries far more than a background pair.
        assertThat(counts.get("US>MX")).isGreaterThan(20 * counts.getOrDefault("BR>JP", 1));
    }

    @Test
    void paymentSizesStayWithinBounds() {
        SplittableRandom rng = new SplittableRandom(2);
        double[] v = new double[50_000];
        for (int i = 0; i < v.length; i++) {
            v[i] = Corridors.usdAmount(rng, Map.of());
            assertThat(v[i]).isBetween(1.0, 900_000.0);
        }
        java.util.Arrays.sort(v);
        assertThat(v[v.length / 2]).isBetween(200.0, 2_000.0); // retail-dominated median
        assertThat(v[(int) (v.length * 0.99)]).isGreaterThan(50_000.0); // corporate tail
    }
}
