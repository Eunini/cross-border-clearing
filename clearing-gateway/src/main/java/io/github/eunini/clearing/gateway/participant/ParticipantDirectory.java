package io.github.eunini.clearing.gateway.participant;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-mostly cache of the participant table. Reference data (BIC, country,
 * currency, status) is served from memory on the hot path; limits used by the
 * risk check are always read from the database under a row lock.
 */
@Component
public class ParticipantDirectory {

    private final JdbcClient jdbc;
    private volatile Map<String, Participant> byBic = Map.of();
    private volatile Map<Integer, Participant> byId = Map.of();

    public ParticipantDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
        load();
    }

    /** Re-reads the participant table (after admin changes). */
    public void reload() {
        load();
    }

    private void load() {
        List<Participant> all = jdbc.sql("""
                        select id, bic, name, country, currency, net_debit_cap, active
                        from participant order by id""")
                .query((rs, n) -> new Participant(rs.getInt("id"), rs.getString("bic"), rs.getString("name"),
                        rs.getString("country"), rs.getString("currency"), rs.getLong("net_debit_cap"),
                        rs.getBoolean("active")))
                .list();
        byBic = all.stream().collect(Collectors.toUnmodifiableMap(Participant::bic, Function.identity()));
        byId = all.stream().collect(Collectors.toUnmodifiableMap(Participant::id, Function.identity()));
    }

    /** Looks up by BIC8 or BIC11 (branch "XXX" or any branch maps to the BIC8 participant). */
    public Optional<Participant> byBic(String bic) {
        if (bic == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byBic.get(bic.length() == 11 ? bic.substring(0, 8) : bic));
    }

    public Participant byId(int id) {
        Participant p = byId.get(id);
        if (p == null) {
            throw new IllegalArgumentException("Unknown participant id " + id);
        }
        return p;
    }

    public Collection<Participant> all() {
        return byId.values().stream().sorted((a, b) -> Integer.compare(a.id(), b.id())).toList();
    }

    @Transactional
    public Participant updateCap(String bic, long netDebitCap) {
        if (netDebitCap < 0) {
            throw new IllegalArgumentException("netDebitCap must be >= 0");
        }
        Participant p = byBic(bic).orElseThrow(() -> new IllegalArgumentException("Unknown participant " + bic));
        jdbc.sql("update participant set net_debit_cap = :cap where id = :id")
                .param("cap", netDebitCap).param("id", p.id()).update();
        reload();
        return byId(p.id());
    }

    @Transactional
    public Participant setActive(String bic, boolean active) {
        Participant p = byBic(bic).orElseThrow(() -> new IllegalArgumentException("Unknown participant " + bic));
        jdbc.sql("update participant set active = :a where id = :id").param("a", active).param("id", p.id()).update();
        reload();
        return byId(p.id());
    }
}
