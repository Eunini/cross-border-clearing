package io.github.eunini.clearing.loadgen;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Synthetic corridor model. Each corridor is a pair of country groups with a
 * weight (share of traffic) and a directional skew (share flowing from the
 * first group to the second). The weights are illustrative, chosen to mimic
 * well-known patterns (remittance corridors are strongly one-directional,
 * trade and treasury corridors are closer to balanced); they are not
 * calibrated to any real statistics.
 */
final class Corridors {

    record Corridor(List<String> from, List<String> to, double weight, double forwardShare) {}

    static final List<String> EU = List.of("DE", "FR", "NL");

    static final List<Corridor> DEFAULT = List.of(
            new Corridor(List.of("US"), List.of("MX"), 15, 0.75),
            new Corridor(List.of("US"), List.of("IN"), 12, 0.80),
            new Corridor(EU, List.of("GB"), 12, 0.50),
            new Corridor(List.of("US"), EU, 10, 0.50),
            new Corridor(List.of("GB"), List.of("IN"), 8, 0.70),
            new Corridor(List.of("HK"), List.of("CN"), 8, 0.50),
            new Corridor(List.of("US"), List.of("CA"), 8, 0.50),
            new Corridor(EU, List.of("CH"), 7, 0.50),
            new Corridor(List.of("JP"), List.of("US"), 7, 0.55),
            new Corridor(List.of("SG"), List.of("IN"), 6, 0.65),
            new Corridor(List.of("US"), List.of("GB"), 6, 0.50),
            new Corridor(List.of("AU"), List.of("SG"), 5, 0.50),
            new Corridor(List.of("US"), List.of("BR"), 4, 0.60));

    /** Share of traffic on uniformly random country pairs outside the named corridors. */
    static final double BACKGROUND_SHARE = 0.05;

    private final double[] cumulative;
    private final List<Corridor> corridors;
    private final List<String> countries;

    Corridors(List<Corridor> corridors, List<String> countries) {
        this.corridors = corridors;
        this.countries = countries;
        cumulative = new double[corridors.size()];
        double acc = 0;
        for (int i = 0; i < corridors.size(); i++) {
            acc += corridors.get(i).weight();
            cumulative[i] = acc;
        }
    }

    /** Returns {debtorCountry, creditorCountry}. */
    String[] pick(SplittableRandom rng) {
        if (rng.nextDouble() < BACKGROUND_SHARE) {
            String a = countries.get(rng.nextInt(countries.size()));
            String b;
            do {
                b = countries.get(rng.nextInt(countries.size()));
            } while (b.equals(a));
            return new String[] {a, b};
        }
        double x = rng.nextDouble() * cumulative[cumulative.length - 1];
        int i = 0;
        while (cumulative[i] <= x) {
            i++;
        }
        Corridor c = corridors.get(i);
        String a = c.from().get(rng.nextInt(c.from().size()));
        String b = c.to().get(rng.nextInt(c.to().size()));
        return rng.nextDouble() < c.forwardShare() ? new String[] {a, b} : new String[] {b, a};
    }

    /** Payment size in USD: a mixture of retail, SME and corporate log-normal segments. */
    static double usdAmount(SplittableRandom rng, Map<String, Double> mix) {
        double u = rng.nextDouble();
        double median;
        double sigma;
        if (u < mix.getOrDefault("retail", 0.70)) {
            median = 300;
            sigma = 1.0;
        } else if (u < mix.getOrDefault("retail", 0.70) + mix.getOrDefault("sme", 0.25)) {
            median = 8_000;
            sigma = 1.0;
        } else {
            median = 150_000;
            sigma = 0.8;
        }
        double z = gaussian(rng);
        return Math.min(900_000, Math.max(1, median * Math.exp(sigma * z)));
    }

    private static double gaussian(SplittableRandom rng) {
        double u1 = Math.max(1e-12, rng.nextDouble());
        double u2 = rng.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

}
