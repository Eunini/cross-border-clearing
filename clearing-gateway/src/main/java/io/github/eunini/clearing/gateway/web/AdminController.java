package io.github.eunini.clearing.gateway.web;

import io.github.eunini.clearing.gateway.cycle.CycleRepository;
import io.github.eunini.clearing.gateway.cycle.CycleService;
import io.github.eunini.clearing.gateway.cycle.CycleSummary;
import io.github.eunini.clearing.gateway.cycle.StatementService;
import io.github.eunini.clearing.gateway.lsm.LsmService;
import io.github.eunini.clearing.gateway.participant.Participant;
import io.github.eunini.clearing.gateway.participant.ParticipantDirectory;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cycle, participant and operations API. The admin endpoints are
 * unauthenticated in this simulation; see the README's limitations.
 */
@RestController
public class AdminController {

    private final CycleService cycleService;
    private final CycleRepository cycles;
    private final StatementService statements;
    private final LsmService lsm;
    private final ParticipantDirectory participants;
    private final JdbcClient jdbc;

    public AdminController(CycleService cycleService, CycleRepository cycles, StatementService statements,
                           LsmService lsm, ParticipantDirectory participants, JdbcClient jdbc) {
        this.cycleService = cycleService;
        this.cycles = cycles;
        this.statements = statements;
        this.lsm = lsm;
        this.participants = participants;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/admin/cycles/close")
    public CycleSummary closeCycle() {
        return cycleService.closeNetAndSettle();
    }

    @PostMapping("/api/admin/lsm/run")
    public LsmService.RunResult runLsm() {
        return lsm.runOnce();
    }

    @GetMapping("/api/cycles")
    public List<CycleSummary> cycles(@RequestParam(defaultValue = "20") int limit) {
        return cycles.recent(Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/api/cycles/{id}")
    public CycleSummary cycle(@PathVariable long id) {
        return cycles.find(id).orElseThrow(() -> new NoSuchElementException("No cycle " + id));
    }

    public record PositionView(String bic, long grossDebit, long grossCredit, long net) {}

    @GetMapping("/api/cycles/{id}/positions")
    public List<PositionView> positions(@PathVariable long id) {
        return jdbc.sql("""
                        select p.bic, cp.gross_debit, cp.gross_credit, cp.net from cycle_position cp
                        join participant p on p.id = cp.participant_id where cp.cycle_id = :id order by p.id""")
                .param("id", id)
                .query((rs, n) -> new PositionView(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)))
                .list();
    }

    public record TransferView(String from, String to, long amount, String status) {}

    @GetMapping("/api/cycles/{id}/transfers")
    public List<TransferView> transfers(@PathVariable long id) {
        return jdbc.sql("""
                        select f.bic, t.bic, si.amount, si.status from settlement_instruction si
                        join participant f on f.id = si.from_participant join participant t on t.id = si.to_participant
                        where si.cycle_id = :id order by si.id""")
                .param("id", id)
                .query((rs, n) -> new TransferView(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)))
                .list();
    }

    @GetMapping(path = "/api/cycles/{id}/statements/{bic}", produces = MediaType.APPLICATION_XML_VALUE)
    public String statement(@PathVariable long id, @PathVariable String bic) {
        return statements.statementXml(id, bic);
    }

    @GetMapping("/api/participants")
    public List<Participant> participantList() {
        return List.copyOf(participants.all());
    }

    public record CapUpdate(long netDebitCap) {}

    @PutMapping("/api/admin/participants/{bic}/cap")
    public Participant updateCap(@PathVariable String bic, @RequestBody CapUpdate update) {
        return participants.updateCap(bic, update.netDebitCap());
    }

    /** Scales every participant's cap by {@code factor} (used by load scenarios to create liquidity pressure). */
    @PostMapping("/api/admin/participants/caps/scale")
    public Map<String, Object> scaleCaps(@RequestParam double factor, @RequestParam(defaultValue = "false") boolean reset) {
        if (factor <= 0) {
            throw new IllegalArgumentException("factor must be > 0");
        }
        for (Participant p : participants.all()) {
            long base = reset ? baseCap(p) : p.netDebitCap();
            participants.updateCap(p.bic(), Math.round(base * factor));
        }
        return Map.of("participants", participants.all().size(), "factor", factor, "fromSeedCaps", reset);
    }

    /** Seed cap by tier (position of the bank within its country), mirroring V2__participants.sql. */
    private long baseCap(Participant p) {
        long rank = participants.all().stream()
                .filter(o -> o.country().equals(p.country()) && o.id() < p.id()).count();
        return rank == 0 ? 2_000_000_000L : rank == 1 ? 800_000_000L : 300_000_000L;
    }

    public record Notification(long eventId, String eventType, String payload, java.time.Instant deliveredAt) {}

    @GetMapping("/api/participants/{bic}/notifications")
    public List<Notification> notifications(@PathVariable String bic, @RequestParam(defaultValue = "0") long afterId,
                                            @RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                        select event_id, event_type, payload::text, delivered_at from participant_notification
                        where recipient = :r and event_id > :after order by event_id limit :n""")
                .param("r", bic).param("after", afterId).param("n", Math.min(Math.max(limit, 1), 1000))
                .query((rs, n) -> new Notification(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .list();
    }
}
