package io.github.eunini.clearing.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.cycle.CycleServiceAccess;
import io.github.eunini.clearing.gateway.engine.EngineModels;
import io.github.eunini.clearing.gateway.engine.HttpNettingEngine;
import io.github.eunini.clearing.gateway.support.FakeNettingEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/**
 * Contract test against a running Rust engine ({@code ENGINE_URL}, e.g.
 * http://localhost:7070): the engine and the Java reference implementation
 * must agree on every net position, and both LSM implementations must release
 * exactly the same set (the maximum FIFO-preserving feasible set is unique).
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_URL", matches = ".+")
class RustEngineContractIT {

    final HttpNettingEngine rust = new HttpNettingEngine(RestClient.builder(), new ClearingProperties("USD",
            Duration.ofSeconds(30), 500, 1 << 20, 100_000_000L,
            new ClearingProperties.Scheduling(false, Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(200), 100),
            new ClearingProperties.Engine(System.getenv("ENGINE_URL"), Duration.ofSeconds(2), Duration.ofSeconds(60), 2),
            new ClearingProperties.Fx(1, Duration.ofSeconds(1), 1)));
    final FakeNettingEngine reference = new FakeNettingEngine();
    static final String[] CCY = {"USD", "EUR", "GBP", "JPY", "INR", "MXN"};

    static List<EngineModels.Obligation> obligations(int n, int participants, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        List<EngineModels.Obligation> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int d = rng.nextInt(participants);
            int c = (d + 1 + rng.nextInt(participants - 1)) % participants;
            long s = 1 + rng.nextLong(5_000_000);
            out.add(new EngineModels.Obligation(1000 + i, "P%02d".formatted(d), "P%02d".formatted(c),
                    CCY[rng.nextInt(CCY.length)], s * 3, s));
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 500, 50_000})
    void nettingAgreesWithReference(int n) {
        var obs = obligations(n, 40, n);
        var req = new EngineModels.NettingRequest("contract-" + n, "USD", 2, "PRECOMPUTED", List.of(), obs);
        var r = rust.net(req);
        var expected = reference.net(req);
        assertThat(r.positions()).containsExactlyInAnyOrderElementsOf(expected.positions());
        assertThat(r.currencyPositions()).containsExactlyInAnyOrderElementsOf(expected.currencyPositions());
        assertThat(r.stats().grossSettlementValue()).isEqualTo(expected.stats().grossSettlementValue());
        assertThat(r.stats().netSettlementValue()).isEqualTo(expected.stats().netSettlementValue());
        assertThat(CycleServiceAccess.reconcile(obs, r)).isEmpty();
        long nonZero = r.positions().stream().filter(p -> p.net() != 0).count();
        assertThat(r.transfers().size()).isLessThanOrEqualTo((int) Math.max(0, nonZero - 1));
    }

    @Test
    void lsmReleasesTheSameSetAsTheReference() {
        SplittableRandom rng = new SplittableRandom(99);
        for (int round = 0; round < 200; round++) {
            int k = 2 + rng.nextInt(8);
            List<EngineModels.Account> accounts = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                accounts.add(new EngineModels.Account("B" + i, rng.nextLong(-500, 500), rng.nextLong(0, 400)));
            }
            List<EngineModels.QueuedPayment> queue = new ArrayList<>();
            int n = rng.nextInt(40);
            for (int i = 0; i < n; i++) {
                int d = rng.nextInt(k);
                int c = (d + 1 + rng.nextInt(k - 1)) % k;
                queue.add(new EngineModels.QueuedPayment(i, "B" + d, "B" + c, 1 + rng.nextLong(600)));
            }
            var req = new EngineModels.LsmRequest(accounts, queue);
            Set<Long> fromRust = new HashSet<>(rust.resolve(req).released());
            Set<Long> fromReference = new HashSet<>(reference.resolve(req).released());
            assertThat(fromRust).as("round %d", round).isEqualTo(fromReference);
        }
    }
}
