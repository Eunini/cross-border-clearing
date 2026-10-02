package io.github.eunini.clearing.gateway.cycle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CycleRepository {

    private final JdbcClient jdbc;

    public CycleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CycleSummary> find(long id) {
        return jdbc.sql("select * from clearing_cycle where id = :id").param("id", id)
                .query(CycleRepository::map).optional();
    }

    public List<CycleSummary> recent(int limit) {
        return jdbc.sql("select * from clearing_cycle order by id desc limit :n").param("n", limit)
                .query(CycleRepository::map).list();
    }

    public List<Long> idsInStatus(String status) {
        return jdbc.sql("select id from clearing_cycle where status = :s order by id").param("s", status)
                .query(Long.class).list();
    }

    public Optional<CycleSummary> open() {
        return jdbc.sql("select * from clearing_cycle where status = 'OPEN'").query(CycleRepository::map).optional();
    }

    static CycleSummary map(ResultSet rs, int n) throws SQLException {
        return new CycleSummary(rs.getLong("id"), rs.getObject("business_date", java.time.LocalDate.class),
                rs.getString("status"), ts(rs, "opened_at"), ts(rs, "closed_at"), ts(rs, "netted_at"),
                ts(rs, "settled_at"), (Integer) rs.getObject("payment_count"), (Long) rs.getObject("gross_value"),
                (Long) rs.getObject("net_value"), (Integer) rs.getObject("transfer_count"),
                (Integer) rs.getObject("transfer_lower_bound"), (Boolean) rs.getObject("transfers_optimal"),
                rs.getString("plan_method"), (Long) rs.getObject("netting_micros"),
                (Long) rs.getObject("netting_round_trip_ms"), rs.getBigDecimal("liquidity_saving_pct"),
                rs.getBigDecimal("transfer_reduction_pct"), rs.getString("failure_reason"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
