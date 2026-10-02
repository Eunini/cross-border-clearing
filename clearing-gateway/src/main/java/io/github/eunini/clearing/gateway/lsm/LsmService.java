package io.github.eunini.clearing.gateway.lsm;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.engine.EngineModels;
import io.github.eunini.clearing.gateway.engine.NettingEngine;
import io.github.eunini.clearing.gateway.iso.ReasonCode;
import io.github.eunini.clearing.gateway.iso.Rejection;
import io.github.eunini.clearing.gateway.outbox.OutboxRepository;
import io.github.eunini.clearing.gateway.outbox.OutboxRepository.NewEvent;
import io.github.eunini.clearing.gateway.participant.Participant;
import io.github.eunini.clearing.gateway.participant.ParticipantDirectory;
import io.github.eunini.clearing.gateway.payment.Lifecycle;
import io.github.eunini.clearing.gateway.payment.PaymentRepository;
import io.github.eunini.clearing.gateway.payment.PaymentState;
import io.github.eunini.clearing.gateway.risk.PositionRow;
import io.github.eunini.clearing.gateway.risk.RiskService;
import io.github.eunini.clearing.gateway.validation.CurrencyRules;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Liquidity-saving mechanism. Periodically offers the queue to the engine's
 * gridlock resolver and settles the released set atomically.
 *
 * <p>The engine works on a snapshot. Before applying its answer, positions
 * are locked and the released set is re-checked against the <em>current</em>
 * positions with the same floor rule the engine uses; a stale answer is
 * discarded and the next run tries again. Payments whose FX quote expired
 * while queued are rejected AB01 and their EndToEndId is released.
 */
@Service
public class LsmService {

    private static final Logger log = LoggerFactory.getLogger(LsmService.class);
    private static final int MAX_QUEUE_PER_RUN = 50_000;

    public record RunResult(int queued, int released, long releasedValue, long releasedByOffsetting, int expired) {}

    private record Queued(long id, String uetr, int debtorId, int creditorId, long amount) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final NettingEngine engine;
    private final RiskService risk;
    private final PaymentRepository payments;
    private final ParticipantDirectory participants;
    private final OutboxRepository outbox;
    private final Clock clock;
    private final ClearingProperties props;
    private final ReentrantLock lock = new ReentrantLock();

    public LsmService(JdbcClient jdbc, TransactionTemplate tx, NettingEngine engine, RiskService risk,
                      PaymentRepository payments, ParticipantDirectory participants, OutboxRepository outbox,
                      Clock clock, ClearingProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.engine = engine;
        this.risk = risk;
        this.payments = payments;
        this.participants = participants;
        this.outbox = outbox;
        this.clock = clock;
        this.props = props;
    }

    public RunResult runOnce() {
        lock.lock();
        try {
            int expired = expireQueued();
            List<Queued> queue = jdbc.sql("""
                            select id, uetr, debtor_participant, creditor_participant, settlement_amount
                            from payment where state = 'QUEUED' order by queued_at, id limit :n""")
                    .param("n", MAX_QUEUE_PER_RUN)
                    .query((rs, i) -> new Queued(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                            rs.getLong(5)))
                    .list();
            if (queue.isEmpty()) {
                return new RunResult(0, 0, 0, 0, expired);
            }
            List<EngineModels.Account> accounts = jdbc.sql("""
                            select p.bic, pp.position, p.net_debit_cap
                            from participant_position pp join participant p on p.id = pp.participant_id
                            order by p.id""")
                    .query((rs, i) -> new EngineModels.Account(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                    .list();
            var request = new EngineModels.LsmRequest(accounts, queue.stream()
                    .map(q -> new EngineModels.QueuedPayment(q.id(), bic(q.debtorId()), bic(q.creditorId()), q.amount()))
                    .toList());
            EngineModels.LsmResult result = engine.resolve(request);
            if (result.released().isEmpty()) {
                return new RunResult(queue.size(), 0, 0, 0, expired);
            }
            Map<Long, Queued> byId = new HashMap<>();
            queue.forEach(q -> byId.put(q.id(), q));
            List<Queued> released = result.released().stream().map(byId::get).toList();
            Boolean applied = tx.execute(status -> apply(released, result.stats()));
            if (!Boolean.TRUE.equals(applied)) {
                log.info("LSM result for {} payments was stale; will retry", released.size());
                return new RunResult(queue.size(), 0, 0, 0, expired);
            }
            log.info("LSM released {} of {} queued payments ({} only via offsetting)",
                    released.size(), queue.size(), result.stats().releasedByOffsetting());
            return new RunResult(queue.size(), released.size(), result.stats().releasedValue(),
                    result.stats().releasedByOffsetting(), expired);
        } finally {
            lock.unlock();
        }
    }

    private String bic(int participantId) {
        return participants.byId(participantId).bic();
    }

    private boolean apply(List<Queued> released, EngineModels.LsmStats stats) {
        long cycleId = risk.lockOpenCycle();
        Map<Integer, PositionRow> rows = risk.lockAllPositions();
        List<Long> ids = released.stream().map(Queued::id).toList();
        Integer stillQueued = jdbc.sql("""
                        select count(*) from (select id from payment where id in (:ids) and state = 'QUEUED'
                        for update) x""").param("ids", ids).query(Integer.class).single();
        if (stillQueued != ids.size()) {
            return false;
        }
        Map<Integer, Long> delta = new HashMap<>();
        Map<Integer, Integer> dequeued = new HashMap<>();
        for (Queued q : released) {
            delta.merge(q.debtorId(), -q.amount(), Long::sum);
            delta.merge(q.creditorId(), q.amount(), Long::sum);
            dequeued.merge(q.debtorId(), -1, Integer::sum);
        }
        for (var e : delta.entrySet()) {
            PositionRow row = rows.get(e.getKey());
            if (row.position() + e.getValue() < row.floor()) {
                return false; // positions moved since the snapshot; retry next run
            }
        }
        risk.applyDeltas(delta, dequeued);
        Instant now = clock.instant();
        jdbc.sql("""
                        update payment set state = 'ACCEPTED', cycle_id = :c, accepted_at = :at, updated_at = :at
                        where id in (:ids)""")
                .param("c", cycleId).param("at", Timestamp.from(now)).param("ids", ids).update();
        List<Lifecycle.Event> events = new ArrayList<>(released.size());
        List<NewEvent> notifications = new ArrayList<>(released.size());
        for (Queued q : released) {
            events.addAll(Lifecycle.resume(q.id(), PaymentState.QUEUED)
                    .to(PaymentState.ACCEPTED, "released by LSM into cycle " + cycleId).events());
            Participant creditor = participants.byId(q.creditorId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("uetr", q.uetr());
            payload.put("debtorAgent", bic(q.debtorId()));
            payload.put("creditorAgent", creditor.bic());
            payload.put("settlementAmount", CurrencyRules.toMajor(q.amount(), props.settlementCurrency()).toPlainString());
            payload.put("cycleId", cycleId);
            payload.put("releasedBy", "LSM");
            notifications.add(new NewEvent("payment", q.uetr(), "PaymentAccepted", creditor.bic(), payload));
        }
        payments.insertEvents(events);
        outbox.append(notifications);
        jdbc.sql("""
                        insert into lsm_run (queued, released, released_value, released_by_offsetting, compute_micros)
                        values (:q, :r, :v, :o, :us)""")
                .param("q", stats.queued()).param("r", stats.released()).param("v", stats.releasedValue())
                .param("o", stats.releasedByOffsetting()).param("us", stats.computeMicros()).update();
        return true;
    }

    /** Rejects queued payments whose FX quote has expired (AB01). */
    int expireQueued() {
        Integer n = tx.execute(status -> {
            List<Queued> expired = jdbc.sql("""
                            select id, uetr, debtor_participant, creditor_participant, settlement_amount
                            from payment where state = 'QUEUED' and quote_expires_at < :now
                            order by id for update skip locked""")
                    .param("now", Timestamp.from(clock.instant()))
                    .query((rs, i) -> new Queued(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                            rs.getLong(5)))
                    .list();
            if (expired.isEmpty()) {
                return 0;
            }
            Map<Integer, Integer> dequeued = new HashMap<>();
            expired.forEach(q -> dequeued.merge(q.debtorId(), -1, Integer::sum));
            risk.lockPositions(dequeued.keySet());
            risk.applyDeltas(Map.of(), dequeued);
            Rejection r = Rejection.of(ReasonCode.AB01, "FX quote expired while queued for liquidity");
            List<Lifecycle.Event> events = new ArrayList<>();
            List<NewEvent> notifications = new ArrayList<>();
            for (Queued q : expired) {
                payments.markRejected(q.id(), r);
                events.addAll(Lifecycle.resume(q.id(), PaymentState.QUEUED).reject(r).events());
                notifications.add(new NewEvent("payment", q.uetr(), "PaymentRejected", bic(q.debtorId()),
                        Map.of("uetr", q.uetr(), "reasonCode", r.code().name(), "reason", r.detail())));
            }
            jdbc.sql("delete from end_to_end_ref where uetr in (:u)")
                    .param("u", expired.stream().map(q -> UUID.fromString(q.uetr())).toList()).update();
            payments.insertEvents(events);
            outbox.append(notifications);
            return expired.size();
        });
        if (n != null && n > 0) {
            log.info("Rejected {} queued payments with expired quotes (AB01)", n);
        }
        return n == null ? 0 : n;
    }
}
