package io.github.eunini.clearing.gateway.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox and delivers events to the simulated participant inboxes.
 *
 * <p>Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several
 * publisher instances can run concurrently without double-claiming. Delivery
 * is at-least-once; the sink deduplicates on the event id, so redelivery after
 * a crash between delivery and marking is harmless. In a real deployment the
 * sink would be a message broker or participant API; only
 * {@link #deliver(List)} would change.
 */
@Component
public class OutboxPublisher {

    record Row(long id, String type, String recipient, String payload) {}

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public OutboxPublisher(JdbcClient jdbc, JdbcTemplate template, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.template = template;
        Gauge.builder("clearing.outbox.backlog", this, OutboxPublisher::backlog).register(meters);
    }

    /** Publishes up to {@code batchSize} events; returns how many were published. */
    @Transactional
    public int publishBatch(int batchSize) {
        List<Row> rows = jdbc.sql("""
                        select id, event_type, recipient, payload::text from outbox_event
                        where published_at is null order by id limit :n for update skip locked""")
                .param("n", batchSize)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
        if (rows.isEmpty()) {
            return 0;
        }
        deliver(rows);
        jdbc.sql("update outbox_event set published_at = now() where id in (:ids)")
                .param("ids", rows.stream().map(Row::id).toList()).update();
        return rows.size();
    }

    void deliver(List<Row> rows) {
        template.batchUpdate("""
                        insert into participant_notification (event_id, recipient, event_type, payload)
                        values (?, ?, ?, cast(? as jsonb)) on conflict (event_id) do nothing""",
                rows, 500, (ps, r) -> {
                    ps.setLong(1, r.id());
                    ps.setString(2, r.recipient() == null ? "BROADCAST" : r.recipient());
                    ps.setString(3, r.type());
                    ps.setString(4, r.payload());
                });
    }

    public long backlog() {
        return jdbc.sql("select count(*) from outbox_event where published_at is null").query(Long.class).single();
    }
}
