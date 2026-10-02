package io.github.eunini.clearing.gateway.payment;

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
