package io.github.eunini.clearing.gateway.risk;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-settlement risk check against net debit caps.
 *
 * <p>Each participant has one position row holding its unsettled net position
 * (all accepted, not yet settled payments). Accepting a payment locks the
 * debtor and creditor rows (always in ascending id order, so concurrent
 * payments cannot deadlock on each other), checks that the debtor stays at or
 * above {@code -netDebitCap}, and moves the amount. Because settlement is
 * guaranteed once the position is reserved, the creditor's position can
 * immediately be used to fund its own outgoing payments.
 *
 * <p>A payment that does not fit - or whose debtor already has queued
 * payments (FIFO) - is queued for the liquidity-saving mechanism instead of
 * being rejected outright.
 */
@Service
public class RiskService {

    private final JdbcClient jdbc;

    public RiskService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the id of the open cycle, holding a FOR SHARE lock so the cycle
     * cannot be cut off while this transaction assigns payments to it. If a
     * cut-off commits while we wait, the open row changes; re-read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long lockOpenCycle() {
        for (int attempt = 0; attempt < 5; attempt++) {
            var id = jdbc.sql("select id from clearing_cycle where status = 'OPEN' for share")
                    .query(Long.class).optional();
            if (id.isPresent()) {
                return id.get();
            }
        }
        throw new IllegalStateException("No open clearing cycle");
    }

    /** Locks the given participants' position rows in ascending id order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Integer, PositionRow> lockPositions(Collection<Integer> participantIds) {
        List<Integer> ids = participantIds.stream().distinct().sorted().toList();
        return jdbc.sql("""
                        select pp.participant_id, pp.position, pp.queued_count, p.net_debit_cap
                        from participant_position pp join participant p on p.id = pp.participant_id
                        where pp.participant_id in (:ids)
                        order by pp.participant_id
                        for update of pp""")
                .param("ids", ids)
                .query((rs, n) -> new PositionRow(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getLong(4)))
                .list().stream()
                .collect(Collectors.toMap(PositionRow::participantId, Function.identity()));
    }

    /** Locks every position row (ascending id order). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Integer, PositionRow> lockAllPositions() {
        return jdbc.sql("""
                        select pp.participant_id, pp.position, pp.queued_count, p.net_debit_cap
                        from participant_position pp join participant p on p.id = pp.participant_id
                        order by pp.participant_id
                        for update of pp""")
                .query((rs, n) -> new PositionRow(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getLong(4)))
                .list().stream()
                .collect(Collectors.toMap(PositionRow::participantId, Function.identity()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RiskDecision reserve(int debtorId, int creditorId, long settlementAmount) {
        long cycleId = lockOpenCycle();
        Map<Integer, PositionRow> rows = lockPositions(List.of(debtorId, creditorId));
        PositionRow debtor = rows.get(debtorId);
        if (debtor.queuedCount() > 0) {
            incrementQueued(debtorId, 1);
            return RiskDecision.queue("FIFO: debtor agent has " + debtor.queuedCount() + " queued payment(s)");
        }
        if (debtor.position() - settlementAmount < debtor.floor()) {
            incrementQueued(debtorId, 1);
            return RiskDecision.queue("Net debit cap would be exceeded (position " + debtor.position()
                    + ", amount " + settlementAmount + ", cap " + debtor.netDebitCap() + ")");
        }
        jdbc.sql("""
                        update participant_position
                        set position = position + case when participant_id = :d then -:amt else :amt end,
                            updated_at = now()
                        where participant_id in (:d, :c)""")
                .param("d", debtorId).param("c", creditorId).param("amt", settlementAmount).update();
        return RiskDecision.accept(cycleId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementQueued(int participantId, int delta) {
        jdbc.sql("update participant_position set queued_count = queued_count + :d where participant_id = :p")
                .param("d", delta).param("p", participantId).update();
    }

    /** Applies a net change per participant (positions must already be locked). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyDeltas(Map<Integer, Long> positionDelta, Map<Integer, Integer> queuedDelta) {
        for (var e : positionDelta.entrySet()) {
            int queued = queuedDelta.getOrDefault(e.getKey(), 0);
            jdbc.sql("""
                            update participant_position set position = position + :d, queued_count = queued_count + :q,
                                updated_at = now() where participant_id = :p""")
                    .param("d", e.getValue()).param("q", queued).param("p", e.getKey()).update();
        }
        for (var e : queuedDelta.entrySet()) {
            if (!positionDelta.containsKey(e.getKey())) {
                incrementQueued(e.getKey(), e.getValue());
            }
        }
    }
}
