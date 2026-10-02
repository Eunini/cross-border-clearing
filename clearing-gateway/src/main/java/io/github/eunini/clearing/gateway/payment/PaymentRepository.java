package io.github.eunini.clearing.gateway.payment;

import io.github.eunini.clearing.gateway.fx.FxQuote;
import io.github.eunini.clearing.gateway.iso.CreditTransfer;
import io.github.eunini.clearing.gateway.iso.Rejection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public PaymentRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /**
     * Claims the UETR. Returns the new payment id, or empty if a payment with
     * this UETR already exists (the caller then performs an idempotent replay).
     * Concurrent claims of the same UETR serialise on the unique index: the
     * loser waits for the winner to commit and then sees the conflict.
     */
    public Optional<Long> insertReceived(UUID uetr, Long messageId, String txSha256, CreditTransfer tx,
                                         Integer debtorId, Integer creditorId, long amountMinor) {
        return jdbc.sql("""
                        insert into payment (uetr, message_id, tx_sha256, end_to_end_id, tx_id, instr_id,
                            debtor_agent, creditor_agent, debtor_participant, creditor_participant,
                            debtor_name, debtor_account, creditor_name, creditor_account,
                            currency, amount, state)
                        values (:uetr, :msg, :sha, :e2e, :txId, :instrId, :dAgent, :cAgent, :dId, :cId,
                            :dName, :dAcct, :cName, :cAcct, :ccy, :amount, 'RECEIVED')
                        on conflict (uetr) do nothing
                        returning id""")
                .param("uetr", uetr).param("msg", messageId).param("sha", txSha256)
                .param("e2e", tx.endToEndId()).param("txId", tx.txId()).param("instrId", tx.instrId())
                .param("dAgent", nz(tx.debtorAgent())).param("cAgent", nz(tx.creditorAgent()))
                .param("dId", debtorId).param("cId", creditorId)
                .param("dName", tx.debtorName()).param("dAcct", truncate(tx.debtorAccount().value(), 34))
                .param("cName", tx.creditorName()).param("cAcct", truncate(tx.creditorAccount().value(), 34))
                .param("ccy", tx.currency()).param("amount", amountMinor)
                .query(Long.class).optional();
    }

    private static String nz(String s) {
        return s == null ? "UNKNOWN" : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public Optional<StoredPayment> findByUetr(UUID uetr) {
        return jdbc.sql("select * from payment where uetr = :uetr").param("uetr", uetr)
                .query(PaymentRepository::map).optional();
    }

    public void markRejected(long id, Rejection r) {
        jdbc.sql("""
                        update payment set state = 'REJECTED', reason_code = :code, reason_text = :text,
                            updated_at = now()
                        where id = :id""")
                .param("code", r.code().name()).param("text", r.detail()).param("id", id).update();
    }

    public void recordQuote(long id, FxQuote q) {
        jdbc.sql("""
                        update payment set target_currency = :tc, target_amount = :ta, settlement_amount = :sa,
                            fx_quote_id = :qid, fx_source_rate = :sr, fx_target_rate = :tr, quote_expires_at = :exp
                        where id = :id""")
                .param("tc", q.targetCurrency()).param("ta", q.targetAmount()).param("sa", q.settlementAmount())
                .param("qid", q.id()).param("sr", q.sourceRate()).param("tr", q.targetRate())
                .param("exp", Timestamp.from(q.expiresAt())).param("id", id).update();
    }

    public void markAccepted(long id, long cycleId, Instant at) {
        jdbc.sql("""
                        update payment set state = 'ACCEPTED', cycle_id = :cycle, accepted_at = :at, updated_at = :at
                        where id = :id""")
                .param("cycle", cycleId).param("at", Timestamp.from(at)).param("id", id).update();
    }

    public void markQueued(long id, Instant at) {
        jdbc.sql("update payment set state = 'QUEUED', queued_at = :at, updated_at = :at where id = :id")
                .param("at", Timestamp.from(at)).param("id", id).update();
    }

    /** EndToEndId duplicate detection per debtor agent; false if already used by another UETR. */
    public boolean claimEndToEnd(String debtorAgent, String endToEndId, UUID uetr) {
        int inserted = jdbc.sql("""
                        insert into end_to_end_ref (debtor_agent, end_to_end_id, uetr) values (:a, :e, :u)
                        on conflict do nothing""")
                .param("a", debtorAgent).param("e", endToEndId).param("u", uetr).update();
        return inserted == 1;
    }

    public void insertEvents(List<Lifecycle.Event> events) {
        if (events.isEmpty()) {
            return;
        }
        template.batchUpdate("""
                        insert into payment_event (payment_id, from_state, to_state, reason_code, detail)
                        values (?, ?, ?, ?, ?)""",
                events, events.size(), (ps, e) -> {
                    ps.setLong(1, e.paymentId());
                    ps.setString(2, e.from() == null ? null : e.from().name());
                    ps.setString(3, e.to().name());
                    ps.setString(4, e.reasonCode());
                    ps.setString(5, e.detail() == null || e.detail().length() <= 200
                            ? e.detail() : e.detail().substring(0, 200));
                });
    }

    public record HistoryEntry(PaymentState from, PaymentState to, String reasonCode, String detail, Instant at) {}

    public List<HistoryEntry> history(long paymentId) {
        return jdbc.sql("""
                        select from_state, to_state, reason_code, detail, occurred_at
                        from payment_event where payment_id = :id order by id""")
                .param("id", paymentId)
                .query((rs, n) -> new HistoryEntry(
                        rs.getString("from_state") == null ? null : PaymentState.valueOf(rs.getString("from_state")),
                        PaymentState.valueOf(rs.getString("to_state")), rs.getString("reason_code"),
                        rs.getString("detail"), rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    static StoredPayment map(ResultSet rs, int n) throws SQLException {
        Timestamp accepted = rs.getTimestamp("accepted_at");
        return new StoredPayment(rs.getLong("id"), rs.getObject("uetr", UUID.class), rs.getString("tx_sha256"),
                rs.getString("end_to_end_id"), rs.getString("tx_id"), rs.getString("instr_id"),
                rs.getString("debtor_agent"), rs.getString("creditor_agent"), rs.getString("currency"),
                rs.getLong("amount"), rs.getString("target_currency"), (Long) rs.getObject("target_amount"),
                (Long) rs.getObject("settlement_amount"), PaymentState.valueOf(rs.getString("state")),
                rs.getString("reason_code"), rs.getString("reason_text"), (Long) rs.getObject("cycle_id"),
                rs.getTimestamp("received_at").toInstant(), accepted == null ? null : accepted.toInstant());
    }
}
