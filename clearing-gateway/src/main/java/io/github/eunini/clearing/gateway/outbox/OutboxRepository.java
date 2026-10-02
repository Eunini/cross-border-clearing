package io.github.eunini.clearing.gateway.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Transactional outbox: events are written in the same database transaction
 * as the state change they describe, so a state change is never visible
 * without its event and vice versa. {@link OutboxPublisher} delivers them.
 */
@Repository
public class OutboxRepository {

    public record NewEvent(String aggregateType, String aggregateId, String eventType, String recipient,
                           Map<String, Object> payload) {}

    private final JdbcTemplate template;
    private final ObjectMapper mapper;

    public OutboxRepository(JdbcTemplate template, ObjectMapper mapper) {
        this.template = template;
        this.mapper = mapper;
    }

    public void append(NewEvent e) {
        append(List.of(e));
    }

    public void append(List<NewEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        template.batchUpdate("""
                        insert into outbox_event (aggregate_type, aggregate_id, event_type, recipient, payload)
                        values (?, ?, ?, ?, cast(? as jsonb))""",
                events, 500, (ps, e) -> {
                    ps.setString(1, e.aggregateType());
                    ps.setString(2, e.aggregateId());
                    ps.setString(3, e.eventType());
                    ps.setString(4, e.recipient());
                    ps.setString(5, json(e.payload()));
                });
    }

    private String json(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unserialisable outbox payload", ex);
        }
    }
}
