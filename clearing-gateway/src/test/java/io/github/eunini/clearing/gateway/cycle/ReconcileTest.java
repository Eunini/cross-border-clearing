package io.github.eunini.clearing.gateway.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.clearing.gateway.engine.EngineModels.NettingRequest;
import io.github.eunini.clearing.gateway.engine.EngineModels.Obligation;
import io.github.eunini.clearing.gateway.engine.EngineModels.NettingResult;
import io.github.eunini.clearing.gateway.engine.EngineModels.Transfer;
import io.github.eunini.clearing.gateway.support.FakeNettingEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconcileTest {

    final List<Obligation> obligations = List.of(
            new Obligation(1, "A", "B", "USD", 100, 100L),
            new Obligation(2, "B", "C", "EUR", 50, 55L),
            new Obligation(3, "C", "A", "GBP", 20, 25L));

    NettingResult result() {
        return new FakeNettingEngine().net(new NettingRequest("c", "USD", 2, "PRECOMPUTED", List.of(), obligations));
    }

    @Test
    void acceptsConsistentResult() {
        assertThat(CycleService.reconcile(obligations, result())).isEmpty();
    }

    @Test
    void detectsWrongTransfers() {
        NettingResult r = result();
        List<Transfer> transfers = new ArrayList<>(r.transfers());
        transfers.set(0, new Transfer(transfers.get(0).from(), transfers.get(0).to(), transfers.get(0).amount() - 1));
        NettingResult bad = new NettingResult(r.cycleId(), r.settlementCurrency(), r.fxMode(), r.positions(),
                r.currencyPositions(), r.currencySummaries(), transfers, r.stats());
        assertThat(CycleService.reconcile(obligations, bad)).get().asString().contains("transfers do not reproduce");
    }

    @Test
    void detectsMissingObligations() {
        assertThat(CycleService.reconcile(obligations.subList(0, 2), result())).get().asString()
                .contains("obligation count");
    }
}
