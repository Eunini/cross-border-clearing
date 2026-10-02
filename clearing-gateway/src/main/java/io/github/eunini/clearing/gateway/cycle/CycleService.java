package io.github.eunini.clearing.gateway.cycle;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.engine.EngineModels;
import io.github.eunini.clearing.gateway.engine.EngineModels.NettingResult;
import io.github.eunini.clearing.gateway.engine.EngineModels.Obligation;
import io.github.eunini.clearing.gateway.engine.EngineUnavailableException;
import io.github.eunini.clearing.gateway.engine.NettingEngine;
import io.github.eunini.clearing.gateway.lsm.LsmService;
import io.github.eunini.clearing.gateway.outbox.OutboxRepository;
import io.github.eunini.clearing.gateway.outbox.OutboxRepository.NewEvent;
import io.github.eunini.clearing.gateway.participant.ParticipantDirectory;
import io.github.eunini.clearing.gateway.risk.RiskService;
import io.github.eunini.clearing.gateway.validation.CurrencyRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Clearing cycle lifecycle: OPEN -> CLOSING -> NETTED -> SETTLED (or FAILED).
 *
 * <ol>
 *   <li><b>Cut-off</b> (one transaction): the open cycle is locked FOR UPDATE,
 *       which waits for in-flight acceptances holding it FOR SHARE, then it is
 *       marked CLOSING and a new cycle is opened.</li>
 *   <li><b>Netting</b>: the cycle's accepted payments are sent to the engine.
 *       The engine's answer is independently reconciled (net positions
 *       recomputed here, transfers must reproduce them exactly) before anything
 *       is persisted. If the engine is unreachable the cycle stays CLOSING and
 *       is retried; a reconciliation mismatch marks it FAILED for an operator.</li>
 *   <li><b>Settlement</b>: the (simulated) settlement agent confirms the
 *       transfers; each participant's position is reduced by its cycle net,
 *       releasing the cap headroom the cycle consumed.</li>
 * </ol>
 *
 * Every step is guarded by a status transition in SQL, so re-running a step
 * after a crash is harmless.
 */
@Service
public class CycleService {

    private static final Logger log = LoggerFactory.getLogger(CycleService.class);

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate tx;
    private final NettingEngine engine;
    private final ParticipantDirectory participants;
    private final RiskService risk;
    private final OutboxRepository outbox;
    private final CycleRepository cycles;
    private final LsmService lsm;
    private final ClearingProperties props;
    private final ReentrantLock lock = new ReentrantLock();

    public CycleService(JdbcClient jdbc, JdbcTemplate template, TransactionTemplate tx, NettingEngine engine,
                        ParticipantDirectory participants, RiskService risk, OutboxRepository outbox,
                        CycleRepository cycles, LsmService lsm, ClearingProperties props) {
        this.jdbc = jdbc;
        this.template = template;
        this.tx = tx;
        this.engine = engine;
        this.participants = participants;
        this.risk = risk;
        this.outbox = outbox;
        this.cycles = cycles;
        this.lsm = lsm;
        this.props = props;
    }

    /** Runs a final LSM pass, cuts off the open cycle, then nets and settles everything pending. */
    public CycleSummary closeNetAndSettle() {
        lock.lock();
        try {
            try {
                lsm.runOnce();
            } catch (EngineUnavailableException e) {
                log.warn("LSM pass before cut-off skipped: {}", e.getMessage());
            }
            long closed = cutOff();
            processPending();
            return cycles.find(closed).orElseThrow();
        } finally {
            lock.unlock();
        }
    }

    /** Scheduled entry point: cut off only if the open cycle has payments; always advance pending cycles. */
    public void scheduledTick() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            Long accepted = jdbc.sql("""
                            select count(*) from payment p join clearing_cycle c on c.id = p.cycle_id
                            where c.status = 'OPEN'""").query(Long.class).single();
            if (accepted > 0) {
                try {
                    lsm.runOnce();
                } catch (EngineUnavailableException e) {
                    log.warn("LSM pass before cut-off skipped: {}", e.getMessage());
                }
                cutOff();
            }
            processPending();
        } finally {
            lock.unlock();
        }
    }

    long cutOff() {
        return tx.execute(status -> {
            long id = jdbc.sql("select id from clearing_cycle where status = 'OPEN' for update")
                    .query(Long.class).single();
            jdbc.sql("update clearing_cycle set status = 'CLOSING', closed_at = now() where id = :id")
                    .param("id", id).update();
            jdbc.sql("insert into clearing_cycle (business_date, status) values (current_date, 'OPEN')").update();
            log.info("Cycle {} cut off", id);
            return id;
        });
    }

    public void processPending() {
        for (long id : cycles.idsInStatus("CLOSING")) {
            try {
                net(id);
            } catch (EngineUnavailableException e) {
                log.warn("Cycle {} stays CLOSING, engine unavailable: {}", id, e.getMessage());
                return; // keep cycle order: do not settle later cycles before this one
            }
        }
        for (long id : cycles.idsInStatus("NETTED")) {
            settle(id);
        }
    }

    void net(long cycleId) {
        List<Obligation> obligations = jdbc.sql("""
                        select p.id, d.bic as debtor, c.bic as creditor, p.currency, p.amount, p.settlement_amount
                        from payment p
                        join participant d on d.id = p.debtor_participant
                        join participant c on c.id = p.creditor_participant
                        where p.cycle_id = :id and p.state = 'ACCEPTED'
                        order by p.id""")
                .param("id", cycleId)
                .query((rs, n) -> new Obligation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getLong(6)))
                .list();
        if (obligations.isEmpty()) {
            tx.executeWithoutResult(s -> jdbc.sql("""
                            update clearing_cycle set status = 'NETTED', netted_at = now(), payment_count = 0,
                                gross_value = 0, net_value = 0, transfer_count = 0, transfer_lower_bound = 0,
                                transfers_optimal = true, plan_method = 'EMPTY', liquidity_saving_pct = 0,
                                transfer_reduction_pct = 0
                            where id = :id and status = 'CLOSING'""").param("id", cycleId).update());
            return;
        }
        var request = new EngineModels.NettingRequest("cycle-" + cycleId, props.settlementCurrency(),
                CurrencyRules.exponent(props.settlementCurrency()), "PRECOMPUTED", List.of(), obligations);
        long started = System.nanoTime();
        NettingResult result = engine.net(request);
        long roundTripMs = (System.nanoTime() - started) / 1_000_000;

        Optional<String> problem = reconcile(obligations, result);
        if (problem.isPresent()) {
            log.error("Cycle {} netting result failed reconciliation: {}", cycleId, problem.get());
            tx.executeWithoutResult(s -> jdbc.sql("""
                            update clearing_cycle set status = 'FAILED', failure_reason = :r
                            where id = :id and status = 'CLOSING'""")
                    .param("r", problem.get()).param("id", cycleId).update());
            return;
        }
        tx.executeWithoutResult(s -> persistNetting(cycleId, result, roundTripMs));
        log.info("Cycle {} netted: {} payments, gross {} net {} ({}% liquidity saved), {} transfers, engine {} us, round trip {} ms",
                cycleId, result.stats().obligationCount(), result.stats().grossSettlementValue(),
                result.stats().netSettlementValue(), result.stats().liquiditySavingPct(),
                result.stats().transferCount(), result.stats().computeMicros(), roundTripMs);
    }

    /**
     * Independent check of the engine's answer: recompute nets from the
     * obligations, require exact agreement, zero sum, and transfers that
     * reproduce every net position.
     */
    static Optional<String> reconcile(List<Obligation> obligations, NettingResult r) {
        if (r.stats().obligationCount() != obligations.size()) {
            return Optional.of("obligation count " + r.stats().obligationCount() + " != " + obligations.size());
        }
        Map<String, Long> expected = new HashMap<>();
        for (Obligation o : obligations) {
            expected.merge(o.debtor(), -o.settlementAmount(), Long::sum);
            expected.merge(o.creditor(), o.settlementAmount(), Long::sum);
        }
        Map<String, Long> reported = new HashMap<>();
        long sum = 0;
        for (var p : r.positions()) {
            reported.put(p.participant(), p.net());
            sum += p.net();
        }
        if (sum != 0) {
            return Optional.of("net positions sum to " + sum);
        }
        for (var e : expected.entrySet()) {
            if (reported.getOrDefault(e.getKey(), 0L).longValue() != e.getValue()) {
                return Optional.of("net of " + e.getKey() + " is " + reported.get(e.getKey()) + ", expected " + e.getValue());
            }
        }
        Map<String, Long> fromTransfers = new HashMap<>();
        for (var t : r.transfers()) {
            if (t.amount() <= 0) {
                return Optional.of("non-positive transfer " + t);
            }
            fromTransfers.merge(t.from(), -t.amount(), Long::sum);
            fromTransfers.merge(t.to(), t.amount(), Long::sum);
        }
        for (var e : reported.entrySet()) {
            if (fromTransfers.getOrDefault(e.getKey(), 0L).longValue() != e.getValue()) {
                return Optional.of("transfers do not reproduce the net of " + e.getKey());
            }
        }
        return Optional.empty();
    }

    private void persistNetting(long cycleId, NettingResult r, long roundTripMs) {
        var s = r.stats();
        int updated = jdbc.sql("""
                        update clearing_cycle set status = 'NETTED', netted_at = now(), payment_count = :n,
                            gross_value = :gross, net_value = :net, transfer_count = :tc, transfer_lower_bound = :lb,
                            transfers_optimal = :opt, plan_method = :method, netting_micros = :us,
                            netting_round_trip_ms = :rt, liquidity_saving_pct = :ls, transfer_reduction_pct = :tr
                        where id = :id and status = 'CLOSING'""")
                .param("n", s.obligationCount()).param("gross", s.grossSettlementValue())
                .param("net", s.netSettlementValue()).param("tc", s.transferCount())
                .param("lb", s.transferLowerBound()).param("opt", s.transfersOptimal())
                .param("method", s.planMethod()).param("us", s.computeMicros()).param("rt", roundTripMs)
                .param("ls", pct(s.liquiditySavingPct())).param("tr", pct(s.transferReductionPct()))
                .param("id", cycleId).update();
        if (updated == 0) {
            return; // already netted by a previous attempt
        }
        template.batchUpdate("""
                        insert into cycle_position (cycle_id, participant_id, gross_debit, gross_credit, net)
                        values (?, ?, ?, ?, ?)""", r.positions(), 100, (ps, p) -> {
                    ps.setLong(1, cycleId);
                    ps.setInt(2, participants.byBic(p.participant()).orElseThrow().id());
                    ps.setLong(3, p.grossDebit());
                    ps.setLong(4, p.grossCredit());
                    ps.setLong(5, p.net());
                });
        template.batchUpdate("""
                        insert into cycle_currency_position (cycle_id, currency, participant_id, gross_debit,
                            gross_credit, net) values (?, ?, ?, ?, ?, ?)""", r.currencyPositions(), 500, (ps, p) -> {
                    ps.setLong(1, cycleId);
                    ps.setString(2, p.currency());
                    ps.setInt(3, participants.byBic(p.participant()).orElseThrow().id());
                    ps.setLong(4, p.grossDebit());
                    ps.setLong(5, p.grossCredit());
                    ps.setLong(6, p.net());
                });
        template.batchUpdate("""
                        insert into settlement_instruction (cycle_id, from_participant, to_participant, amount, status)
                        values (?, ?, ?, ?, 'PENDING')""", r.transfers(), 100, (ps, t) -> {
                    ps.setLong(1, cycleId);
                    ps.setInt(2, participants.byBic(t.from()).orElseThrow().id());
                    ps.setInt(3, participants.byBic(t.to()).orElseThrow().id());
                    ps.setLong(4, t.amount());
                });
        jdbc.sql("""
                        insert into payment_event (payment_id, from_state, to_state, detail)
                        select id, 'ACCEPTED', 'CLEARED', :d from payment where cycle_id = :id and state = 'ACCEPTED'""")
                .param("d", "netted in cycle " + cycleId).param("id", cycleId).update();
        jdbc.sql("update payment set state = 'CLEARED', updated_at = now() where cycle_id = :id and state = 'ACCEPTED'")
                .param("id", cycleId).update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cycleId", cycleId);
        payload.put("payments", s.obligationCount());
        payload.put("grossValue", s.grossSettlementValue());
        payload.put("netValue", s.netSettlementValue());
        payload.put("transfers", s.transferCount());
        outbox.append(new NewEvent("cycle", Long.toString(cycleId), "CycleNetted", null, payload));
    }

    private static BigDecimal pct(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }

    void settle(long cycleId) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            update clearing_cycle set status = 'SETTLED', settled_at = now()
                            where id = :id and status = 'NETTED'""").param("id", cycleId).update();
            if (updated == 0) {
                return;
            }
            risk.lockAllPositions();
            jdbc.sql("""
                            update participant_position pp set position = pp.position - cp.net, updated_at = now()
                            from cycle_position cp
                            where cp.cycle_id = :id and cp.participant_id = pp.participant_id""")
                    .param("id", cycleId).update();
            jdbc.sql("""
                            update settlement_instruction set status = 'CONFIRMED', confirmed_at = now()
                            where cycle_id = :id and status = 'PENDING'""").param("id", cycleId).update();
            jdbc.sql("""
                            insert into payment_event (payment_id, from_state, to_state, detail)
                            select id, 'CLEARED', 'SETTLED', :d from payment where cycle_id = :id and state = 'CLEARED'""")
                    .param("d", "settled in cycle " + cycleId).param("id", cycleId).update();
            jdbc.sql("update payment set state = 'SETTLED', updated_at = now() where cycle_id = :id and state = 'CLEARED'")
                    .param("id", cycleId).update();
            List<NewEvent> events = new ArrayList<>();
            jdbc.sql("""
                            select p.bic, cp.net from cycle_position cp join participant p on p.id = cp.participant_id
                            where cp.cycle_id = :id order by p.id""").param("id", cycleId)
                    .query((RowCallbackHandler) rs -> {
                        events.add(new NewEvent("cycle", Long.toString(cycleId), "StatementAvailable",
                                rs.getString(1), Map.of("cycleId", cycleId, "net", rs.getLong(2),
                                        "statement", "/api/cycles/" + cycleId + "/statements/" + rs.getString(1))));
                    });
            outbox.append(events);
            log.info("Cycle {} settled", cycleId);
        });
    }
}
