package io.github.eunini.clearing.gateway.support;

import io.github.eunini.clearing.gateway.engine.EngineModels;
import io.github.eunini.clearing.gateway.engine.EngineModels.Account;
import io.github.eunini.clearing.gateway.engine.EngineModels.LsmResult;
import io.github.eunini.clearing.gateway.engine.EngineModels.NettingResult;
import io.github.eunini.clearing.gateway.engine.EngineModels.Obligation;
import io.github.eunini.clearing.gateway.engine.EngineModels.QueuedPayment;
import io.github.eunini.clearing.gateway.engine.EngineUnavailableException;
import io.github.eunini.clearing.gateway.engine.NettingEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Straightforward in-JVM reference implementation of the engine contract,
 * used by integration tests (the Rust engine has its own property tests, and
 * {@code RustEngineContractIT} checks both implementations agree).
 */
public class FakeNettingEngine implements NettingEngine {

    public final AtomicBoolean down = new AtomicBoolean(false);
    public final AtomicBoolean corruptNextResult = new AtomicBoolean(false);

    @Override
    public NettingResult net(EngineModels.NettingRequest request) {
        if (down.get()) {
            throw new EngineUnavailableException("simulated outage", null);
        }
        Map<String, long[]> pos = new TreeMap<>(); // debit, credit, net
        Map<String, Map<String, long[]>> ccy = new TreeMap<>();
        long gross = 0;
        for (Obligation o : request.obligations()) {
            long s = o.settlementAmount();
            pos.computeIfAbsent(o.debtor(), k -> new long[3]);
            pos.computeIfAbsent(o.creditor(), k -> new long[3]);
            pos.get(o.debtor())[0] += s;
            pos.get(o.debtor())[2] -= s;
            pos.get(o.creditor())[1] += s;
            pos.get(o.creditor())[2] += s;
            var c = ccy.computeIfAbsent(o.currency(), k -> new TreeMap<>());
            c.computeIfAbsent(o.debtor(), k -> new long[3]);
            c.computeIfAbsent(o.creditor(), k -> new long[3]);
            c.get(o.debtor())[0] += o.amount();
            c.get(o.debtor())[2] -= o.amount();
            c.get(o.creditor())[1] += o.amount();
            c.get(o.creditor())[2] += o.amount();
            gross += s;
        }
        List<EngineModels.Position> positions = new ArrayList<>();
        pos.forEach((p, v) -> positions.add(new EngineModels.Position(p, v[0], v[1], v[2])));
        if (corruptNextResult.getAndSet(false) && !positions.isEmpty()) {
            var first = positions.getFirst();
            positions.set(0, new EngineModels.Position(first.participant(), first.grossDebit(), first.grossCredit(),
                    first.net() + 1));
        }
        List<EngineModels.CurrencyPosition> cps = new ArrayList<>();
        List<EngineModels.CurrencySummary> sums = new ArrayList<>();
        ccy.forEach((c, m) -> {
            long g = 0, n = 0;
            for (var e : m.entrySet()) {
                cps.add(new EngineModels.CurrencyPosition(c, e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
                g += e.getValue()[0];
                n += Math.max(0, e.getValue()[2]);
            }
            sums.add(new EngineModels.CurrencySummary(c, 0, g, n));
        });
        // Greedy settlement: payers in name order pay receivers in name order.
        List<EngineModels.Transfer> transfers = new ArrayList<>();
        List<String[]> payers = new ArrayList<>();
        List<String[]> receivers = new ArrayList<>();
        Map<String, Long> rest = new LinkedHashMap<>();
        positions.forEach(p -> rest.put(p.participant(), p.net()));
        for (var e : rest.entrySet()) {
            if (e.getValue() < 0) payers.add(new String[] {e.getKey()});
            if (e.getValue() > 0) receivers.add(new String[] {e.getKey()});
        }
        int i = 0, j = 0;
        while (i < payers.size() && j < receivers.size()) {
            String a = payers.get(i)[0], b = receivers.get(j)[0];
            long amt = Math.min(-rest.get(a), rest.get(b));
            transfers.add(new EngineModels.Transfer(a, b, amt));
            rest.put(a, rest.get(a) + amt);
            rest.put(b, rest.get(b) - amt);
            if (rest.get(a) == 0) i++;
            if (rest.get(b) == 0) j++;
        }
        long net = positions.stream().mapToLong(p -> Math.max(0, p.net())).sum();
        int n = request.obligations().size();
        var stats = new EngineModels.NettingStats(n, positions.size(), ccy.size(), gross, net, transfers.size(),
                Math.max(payers.size(), receivers.size()), false, "TEST_GREEDY",
                n == 0 ? 0 : 100.0 * (1 - (double) transfers.size() / n),
                gross == 0 ? 0 : 100.0 * (1 - (double) net / gross), 1);
        return new NettingResult(request.cycleId(), request.settlementCurrency(), request.fxMode(), positions, cps, sums,
                transfers, stats);
    }

    /** Naive O(n^2) version of the FIFO iterative-removal algorithm. */
    @Override
    public LsmResult resolve(EngineModels.LsmRequest request) {
        if (down.get()) {
            throw new EngineUnavailableException("simulated outage", null);
        }
        Map<String, Account> acct = new HashMap<>();
        request.accounts().forEach(a -> acct.put(a.participant(), a));
        List<QueuedPayment> q = request.queue();
        boolean[] in = new boolean[q.size()];
        java.util.Arrays.fill(in, true);
        long removals = 0;
        while (true) {
            Map<String, Long> p = new HashMap<>();
            acct.values().forEach(a -> p.put(a.participant(), a.balance()));
            for (int k = 0; k < q.size(); k++) {
                if (in[k]) {
                    p.merge(q.get(k).debtor(), -q.get(k).amount(), Long::sum);
                    p.merge(q.get(k).creditor(), q.get(k).amount(), Long::sum);
                }
            }
            String violator = null;
            for (Account a : request.accounts()) {
                if (p.get(a.participant()) < Math.min(a.balance(), -a.limit())) {
                    violator = a.participant();
                    break;
                }
            }
            if (violator == null) {
                List<Long> released = new ArrayList<>(), unresolved = new ArrayList<>();
                long value = 0;
                for (int k = 0; k < q.size(); k++) {
                    if (in[k]) {
                        released.add(q.get(k).id());
                        value += q.get(k).amount();
                    } else {
                        unresolved.add(q.get(k).id());
                    }
                }
                List<EngineModels.AccountBalance> balances = request.accounts().stream()
                        .map(a -> new EngineModels.AccountBalance(a.participant(), p.get(a.participant()))).toList();
                return new LsmResult(released, unresolved, balances,
                        new EngineModels.LsmStats(q.size(), released.size(), value, released.size(), removals, 1));
            }
            for (int k = q.size() - 1; k >= 0; k--) {
                if (in[k] && q.get(k).debtor().equals(violator)) {
                    in[k] = false;
                    removals++;
                    break;
                }
            }
        }
    }
}
