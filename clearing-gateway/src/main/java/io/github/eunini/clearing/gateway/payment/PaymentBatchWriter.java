package io.github.eunini.clearing.gateway.payment;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

/**
 * Set-based SQL for the payment sequencer: one statement per step for a
 * whole batch, using PostgreSQL arrays and {@code unnest}, so a batch of
 * hundreds of payments costs a handful of round trips and one commit.
 * Must run inside a Spring-managed transaction.
 */
@Repository
public class PaymentBatchWriter {

    /** Row to insert when claiming a UETR. */
    public record NewPayment(UUID uetr, Long messageId, String txSha256, String endToEndId, String txId,
                             String instrId, String debtorAgent, String creditorAgent, Integer debtorId,
                             Integer creditorId, String debtorName, String debtorAccount, String creditorName,
                             String creditorAccount, String currency, long amount, String targetCurrency,
                             Long targetAmount, Long settlementAmount, UUID quoteId, BigDecimal sourceRate,
                             BigDecimal targetRate, Timestamp quoteExpiresAt, Timestamp receivedAt) {}

    /** Final state of a claimed payment. */
    public record FinalState(long id, PaymentState state, String reasonCode, String reasonText, Long cycleId,
                             Timestamp acceptedAt, Timestamp queuedAt) {}

    private final DataSource dataSource;

    public PaymentBatchWriter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection connection() {
        return DataSourceUtils.getConnection(dataSource);
    }

    /** Inserts the batch; returns uetr -> id for rows that were claimed (absent = UETR already existed). */
    public Map<UUID, Long> claim(List<NewPayment> rows) throws SQLException {
        Connection c = connection();
        String sql = """
                insert into payment (uetr, message_id, tx_sha256, end_to_end_id, tx_id, instr_id, debtor_agent,
                    creditor_agent, debtor_participant, creditor_participant, debtor_name, debtor_account,
                    creditor_name, creditor_account, currency, amount, target_currency, target_amount,
                    settlement_amount, fx_quote_id, fx_source_rate, fx_target_rate, quote_expires_at, received_at,
                    state, updated_at)
                select u.*, 'RECEIVED', u.received_at
                from unnest(?::uuid[], ?::int8[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[],
                    ?::int4[], ?::int4[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::int8[],
                    ?::text[], ?::int8[], ?::int8[], ?::uuid[], ?::numeric[], ?::numeric[], ?::timestamptz[],
                    ?::timestamptz[])
                    as u(uetr, message_id, tx_sha256, end_to_end_id, tx_id, instr_id, debtor_agent, creditor_agent,
                    debtor_participant, creditor_participant, debtor_name, debtor_account, creditor_name,
                    creditor_account, currency, amount, target_currency, target_amount, settlement_amount,
                    fx_quote_id, fx_source_rate, fx_target_rate, quote_expires_at, received_at)
                on conflict (uetr) do nothing
                returning id, uetr""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setArray(i++, array(c, "uuid", rows, NewPayment::uetr));
            ps.setArray(i++, array(c, "int8", rows, NewPayment::messageId));
            ps.setArray(i++, array(c, "text", rows, NewPayment::txSha256));
            ps.setArray(i++, array(c, "text", rows, NewPayment::endToEndId));
            ps.setArray(i++, array(c, "text", rows, NewPayment::txId));
            ps.setArray(i++, array(c, "text", rows, NewPayment::instrId));
            ps.setArray(i++, array(c, "text", rows, NewPayment::debtorAgent));
            ps.setArray(i++, array(c, "text", rows, NewPayment::creditorAgent));
            ps.setArray(i++, array(c, "int4", rows, NewPayment::debtorId));
            ps.setArray(i++, array(c, "int4", rows, NewPayment::creditorId));
            ps.setArray(i++, array(c, "text", rows, NewPayment::debtorName));
            ps.setArray(i++, array(c, "text", rows, NewPayment::debtorAccount));
            ps.setArray(i++, array(c, "text", rows, NewPayment::creditorName));
            ps.setArray(i++, array(c, "text", rows, NewPayment::creditorAccount));
            ps.setArray(i++, array(c, "text", rows, NewPayment::currency));
            ps.setArray(i++, array(c, "int8", rows, NewPayment::amount));
            ps.setArray(i++, array(c, "text", rows, NewPayment::targetCurrency));
            ps.setArray(i++, array(c, "int8", rows, NewPayment::targetAmount));
            ps.setArray(i++, array(c, "int8", rows, NewPayment::settlementAmount));
            ps.setArray(i++, array(c, "uuid", rows, NewPayment::quoteId));
            ps.setArray(i++, array(c, "numeric", rows, NewPayment::sourceRate));
            ps.setArray(i++, array(c, "numeric", rows, NewPayment::targetRate));
            ps.setArray(i++, array(c, "timestamptz", rows, NewPayment::quoteExpiresAt));
            ps.setArray(i, array(c, "timestamptz", rows, NewPayment::receivedAt));
            Map<UUID, Long> claimed = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claimed.put(rs.getObject(2, UUID.class), rs.getLong(1));
                }
            }
            return claimed;
        }
    }

    public List<StoredPayment> findByUetrs(List<UUID> uetrs) throws SQLException {
        Connection c = connection();
        try (PreparedStatement ps = c.prepareStatement("select * from payment where uetr = any(?)")) {
            ps.setArray(1, c.createArrayOf("uuid", uetrs.toArray()));
            List<StoredPayment> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(PaymentRepository.map(rs, 0));
                }
            }
            return out;
        }
    }

    /** Claims EndToEndIds per debtor agent; returns the UETRs whose claim succeeded. */
    public Set<UUID> claimEndToEnd(List<String> debtorAgents, List<String> endToEndIds, List<UUID> uetrs)
            throws SQLException {
        Connection c = connection();
        try (PreparedStatement ps = c.prepareStatement("""
                insert into end_to_end_ref (debtor_agent, end_to_end_id, uetr)
                select * from unnest(?::text[], ?::text[], ?::uuid[])
                on conflict do nothing returning uetr""")) {
            ps.setArray(1, c.createArrayOf("text", debtorAgents.toArray()));
            ps.setArray(2, c.createArrayOf("text", endToEndIds.toArray()));
            ps.setArray(3, c.createArrayOf("uuid", uetrs.toArray()));
            Set<UUID> ok = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ok.add(rs.getObject(1, UUID.class));
                }
            }
            return ok;
        }
    }

    public void finalizeStates(List<FinalState> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        Connection c = connection();
        try (PreparedStatement ps = c.prepareStatement("""
                update payment p set state = u.state, reason_code = u.code, reason_text = u.text,
                    cycle_id = u.cycle, accepted_at = u.accepted, queued_at = u.queued, updated_at = now()
                from unnest(?::int8[], ?::text[], ?::text[], ?::text[], ?::int8[], ?::timestamptz[], ?::timestamptz[])
                    as u(id, state, code, text, cycle, accepted, queued)
                where p.id = u.id""")) {
            ps.setArray(1, array(c, "int8", rows, FinalState::id));
            ps.setArray(2, array(c, "text", rows, r -> r.state().name()));
            ps.setArray(3, array(c, "text", rows, FinalState::reasonCode));
            ps.setArray(4, array(c, "text", rows, FinalState::reasonText));
            ps.setArray(5, array(c, "int8", rows, FinalState::cycleId));
            ps.setArray(6, array(c, "timestamptz", rows, FinalState::acceptedAt));
            ps.setArray(7, array(c, "timestamptz", rows, FinalState::queuedAt));
            ps.executeUpdate();
        }
    }

    /** Applies {position delta, queued delta} per participant (rows must already be locked). */
    public void applyPositionDeltas(Map<Integer, long[]> deltas) throws SQLException {
        if (deltas.isEmpty()) {
            return;
        }
        Connection c = connection();
        List<Map.Entry<Integer, long[]>> rows = new ArrayList<>(deltas.entrySet());
        try (PreparedStatement ps = c.prepareStatement("""
                update participant_position pp set position = pp.position + u.d,
                    queued_count = pp.queued_count + u.q, updated_at = now()
                from unnest(?::int4[], ?::int8[], ?::int4[]) as u(pid, d, q)
                where pp.participant_id = u.pid""")) {
            ps.setArray(1, array(c, "int4", rows, Map.Entry::getKey));
            ps.setArray(2, array(c, "int8", rows, e -> e.getValue()[0]));
            ps.setArray(3, array(c, "int4", rows, e -> (int) e.getValue()[1]));
            ps.executeUpdate();
        }
    }

    private static <T> Array array(Connection c, String type, List<T> rows, java.util.function.Function<T, ?> f)
            throws SQLException {
        Object[] values = new Object[rows.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = f.apply(rows.get(i));
        }
        return c.createArrayOf(type, values);
    }
}
