package io.github.eunini.clearing.gateway.payment;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.fx.FxQuote;
import io.github.eunini.clearing.gateway.fx.FxRateService;
import io.github.eunini.clearing.gateway.iso.CreditTransfer;
import io.github.eunini.clearing.gateway.iso.ReasonCode;
import io.github.eunini.clearing.gateway.iso.Rejection;
import io.github.eunini.clearing.gateway.outbox.OutboxRepository;
import io.github.eunini.clearing.gateway.outbox.OutboxRepository.NewEvent;
import io.github.eunini.clearing.gateway.participant.Participant;
import io.github.eunini.clearing.gateway.participant.ParticipantDirectory;
import io.github.eunini.clearing.gateway.risk.PositionBook;
import io.github.eunini.clearing.gateway.risk.RiskService;
import io.github.eunini.clearing.gateway.validation.CurrencyRules;
import io.github.eunini.clearing.gateway.validation.PaymentValidator;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment processing pipeline.
 *
 * <p><b>Request threads</b> do everything that needs no shared state: UETR
 * parsing, rulebook validation and the FX quote. The prepared payment is then
 * handed to a <b>single-writer sequencer</b> thread, which drains whatever has
 * queued up (up to {@value #MAX_BATCH}) and processes it as one database
 * transaction: claim UETRs, claim EndToEndIds, lock the involved position rows,
 * decide each payment in arrival order with {@link PositionBook}, then write
 * states, history and outbox events with set-based statements.
 *
 * <p>Why: every payment touches the position rows of its two banks, and a few
 * large banks are in most payments. With one transaction per payment those
 * rows are locked across several round trips plus a commit, which caps the
 * whole network at roughly one commit per hot row at a time. Deciding a batch
 * under one lock acquisition and one commit (application-level group commit)
 * removes that ceiling while keeping the decisions strictly sequential.
 *
 * <p><b>Exactly-once.</b> The UETR is claimed with
 * {@code INSERT ... ON CONFLICT DO NOTHING} in the same transaction as every
 * side effect. An already-claimed UETR with the same content hash returns the
 * stored outcome (a retransmission); with a different hash it is rejected AM05
 * without touching the original. Two submissions of one UETR in the same batch
 * are split: the second runs in a follow-up batch and replays the first.
 */
@Service
public class PaymentProcessor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    static final int MAX_BATCH = 500;
    private static final int MAX_ATTEMPTS = 3;

    /** A validated, quoted payment waiting for the sequencer. */
    static final class Pending {
        final CreditTransfer tx;
        final UUID uetr;
        final String hash;
        final Long messageId;
        final Integer debtorId;
        final Integer creditorId;
        final long amountMinor;
        final PaymentValidator.Validated validated;
        final FxQuote quote;
        final Rejection rejection;
        final CompletableFuture<TxOutcome> result = new CompletableFuture<>();
        final long enqueuedNanos = System.nanoTime();

        Pending(CreditTransfer tx, UUID uetr, Long messageId, Integer debtorId, Integer creditorId, long amountMinor,
                PaymentValidator.Validated validated, FxQuote quote, Rejection rejection) {
            this.tx = tx;
            this.uetr = uetr;
            this.hash = tx.contentHash();
            this.messageId = messageId;
            this.debtorId = debtorId;
            this.creditorId = creditorId;
            this.amountMinor = amountMinor;
            this.validated = validated;
            this.quote = quote;
            this.rejection = rejection;
        }
    }

    private final PaymentBatchWriter writer;
    private final PaymentRepository payments;
    private final PaymentValidator validator;
    private final ParticipantDirectory participants;
    private final FxRateService fx;
    private final RiskService risk;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final JdbcTemplate template;
    private final ClearingProperties props;
    private final Clock clock;
    private final MeterRegistry meters;
    private final DistributionSummary batchSizes;
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>(50_000);
    private volatile boolean running;
    private Thread sequencer;

    public PaymentProcessor(PaymentBatchWriter writer, PaymentRepository payments, PaymentValidator validator,
                            ParticipantDirectory participants, FxRateService fx, RiskService risk,
                            OutboxRepository outbox, TransactionTemplate tx, JdbcTemplate template,
                            ClearingProperties props, Clock clock, MeterRegistry meters) {
        this.writer = writer;
        this.payments = payments;
        this.validator = validator;
        this.participants = participants;
        this.fx = fx;
        this.risk = risk;
        this.outbox = outbox;
        this.tx = tx;
        this.template = template;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
        this.batchSizes = DistributionSummary.builder("clearing.sequencer.batch.size").register(meters);
    }

    // ------------------------------------------------------------------ request side

    /** Validates and quotes on the caller's thread, then queues for the sequencer. */
    public CompletableFuture<TxOutcome> submit(CreditTransfer t, Long messageId) {
        UUID uetr;
        try {
            uetr = t.uetr() == null ? null : UUID.fromString(t.uetr());
        } catch (IllegalArgumentException e) {
            uetr = null;
        }
        if (uetr == null) {
            return CompletableFuture.completedFuture(
                    TxOutcome.untracked(t, Rejection.of(ReasonCode.CH21, "PmtId/UETR is mandatory")));
        }
        Integer debtorId = participants.byBic(t.debtorAgent()).map(Participant::id).orElse(null);
        Integer creditorId = participants.byBic(t.creditorAgent()).map(Participant::id).orElse(null);
        Pending p;
        PaymentValidator.Result result = validator.validate(t);
        if (result instanceof PaymentValidator.Failed f) {
            p = new Pending(t, uetr, messageId, debtorId, creditorId, bestEffortMinor(t), null, null, f.rejection());
        } else {
            PaymentValidator.Validated v = ((PaymentValidator.Ok) result).value();
            FxQuote quote = fx.quote(t.currency(), v.creditor().currency(), v.amountMinor()).orElseThrow();
            Rejection r = null;
            if (quote.settlementAmount() > props.maxSettlementAmountMinor()) {
                r = Rejection.of(ReasonCode.AM02, "Settlement value exceeds the per-payment limit");
            } else if (quote.settlementAmount() <= 0 || quote.targetAmount() <= 0) {
                r = Rejection.of(ReasonCode.AM12, "Amount too small to convert");
            }
            p = new Pending(t, uetr, messageId, debtorId, creditorId, v.amountMinor(), v, quote, r);
        }
        try {
            if (!queue.offer(p, 5, TimeUnit.SECONDS)) {
                p.result.completeExceptionally(new IllegalStateException("Payment sequencer overloaded"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.result.completeExceptionally(e);
        }
        return p.result;
    }

    public TxOutcome process(CreditTransfer t, Long messageId) {
        return submit(t, messageId).join();
    }

    // ------------------------------------------------------------------ sequencer

    private void loop() {
        List<Pending> batch = new ArrayList<>(MAX_BATCH);
        while (running || !queue.isEmpty()) {
            try {
                Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                processBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Sequencer batch failed", e);
                batch.forEach(p -> p.result.completeExceptionally(e));
            } finally {
                batch.clear();
            }
        }
    }

    void processBatch(List<Pending> batch) {
        // Split repeated UETRs so each batch claims every UETR at most once.
        Set<UUID> seen = new HashSet<>();
        List<Pending> current = new ArrayList<>(batch.size());
        List<Pending> later = new ArrayList<>();
        for (Pending p : batch) {
            (seen.add(p.uetr) ? current : later).add(p);
        }
        batchSizes.record(current.size());
        Map<Pending, TxOutcome> outcomes = executeWithRetry(current);
        long now = System.nanoTime();
        outcomes.forEach((p, o) -> {
            meters.timer("clearing.payment.processing", "status", o.state().isoStatus(),
                    "replay", Boolean.toString(o.replay())).record(now - p.enqueuedNanos, TimeUnit.NANOSECONDS);
            p.result.complete(o);
        });
        if (!later.isEmpty()) {
            processBatch(later);
        }
    }

    private Map<Pending, TxOutcome> executeWithRetry(List<Pending> batch) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> {
                    try {
                        return executeInTransaction(batch);
                    } catch (SQLException e) {
                        throw template.getExceptionTranslator().translate("payment batch", null, e);
                    }
                });
            } catch (TransientDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Retrying payment batch of {} after transient failure: {}", batch.size(), e.getMessage());
            }
        }
    }

    private Map<Pending, TxOutcome> executeInTransaction(List<Pending> batch) throws SQLException {
        Instant now = clock.instant();
        Timestamp ts = Timestamp.from(now);
        long cycleId = risk.lockOpenCycle();
        Map<Pending, TxOutcome> outcomes = new LinkedHashMap<>();

        // 1. Claim UETRs.
        List<PaymentBatchWriter.NewPayment> rows = batch.stream().map(p -> newPayment(p, ts)).toList();
        Map<UUID, Long> claimed = writer.claim(rows);
        List<UUID> existing = batch.stream().map(p -> p.uetr).filter(u -> !claimed.containsKey(u)).toList();
        Map<UUID, StoredPayment> stored = new HashMap<>();
        if (!existing.isEmpty()) {
            writer.findByUetrs(existing).forEach(s -> stored.put(s.uetr(), s));
        }

        List<Lifecycle.Event> events = new ArrayList<>();
        List<NewEvent> notifications = new ArrayList<>();
        List<PaymentBatchWriter.FinalState> finals = new ArrayList<>();
        Map<Pending, Lifecycle> live = new LinkedHashMap<>();

        for (Pending p : batch) {
            Long id = claimed.get(p.uetr);
            if (id == null) {
                StoredPayment s = stored.get(p.uetr);
                outcomes.put(p, s != null && s.txSha256().equals(p.hash) ? TxOutcome.replay(s)
                        : TxOutcome.untracked(p.tx, Rejection.of(ReasonCode.AM05,
                                "UETR " + p.uetr + " already used by a different payment")));
                continue;
            }
            Lifecycle lc = Lifecycle.received(id);
            if (p.validated == null) {
                reject(p, lc, p.rejection, outcomes, events, notifications, finals);
                continue;
            }
            lc.to(PaymentState.VALIDATED, null);
            lc.to(PaymentState.FX_QUOTED, quoteDetail(p.quote));
            if (p.rejection != null) {
                reject(p, lc, p.rejection, outcomes, events, notifications, finals);
                continue;
            }
            live.put(p, lc);
        }

        // 2. EndToEndId duplicates (claimed last, so rejected payments never hold one).
        if (!live.isEmpty()) {
            List<Pending> candidates = new ArrayList<>(live.keySet());
            Set<UUID> ok = writer.claimEndToEnd(
                    candidates.stream().map(p -> p.validated.debtor().bic()).toList(),
                    candidates.stream().map(p -> p.tx.endToEndId()).toList(),
                    candidates.stream().map(p -> p.uetr).toList());
            for (Pending p : candidates) {
                if (!ok.contains(p.uetr)) {
                    reject(p, live.remove(p), Rejection.of(ReasonCode.AM05, "EndToEndId " + p.tx.endToEndId()
                            + " already used by " + p.validated.debtor().bic()), outcomes, events, notifications, finals);
                }
            }
        }

        // 3. Pre-settlement risk check, in arrival order, under the position locks.
        if (!live.isEmpty()) {
            Set<Integer> ids = new LinkedHashSet<>();
            live.keySet().forEach(p -> {
                ids.add(p.validated.debtor().id());
                ids.add(p.validated.creditor().id());
            });
            PositionBook book = new PositionBook(risk.lockPositions(ids));
            for (var e : live.entrySet()) {
                Pending p = e.getKey();
                Lifecycle lc = e.getValue();
                var decision = book.reserve(p.validated.debtor().id(), p.validated.creditor().id(),
                        p.quote.settlementAmount());
                if (decision == PositionBook.Decision.ACCEPT) {
                    lc.to(PaymentState.ACCEPTED, "cycle " + cycleId);
                    finals.add(new PaymentBatchWriter.FinalState(lc.paymentId(), PaymentState.ACCEPTED, null, null,
                            cycleId, ts, null));
                    notifications.add(acceptedEvent(p.tx, p.validated, p.quote, cycleId));
                    outcomes.put(p, TxOutcome.of(p.tx, PaymentState.ACCEPTED, null, now));
                } else {
                    String reason = decision == PositionBook.Decision.QUEUE_FIFO
                            ? "FIFO: earlier payments of the debtor agent are queued"
                            : "Net debit cap would be exceeded";
                    lc.to(PaymentState.QUEUED, reason);
                    finals.add(new PaymentBatchWriter.FinalState(lc.paymentId(), PaymentState.QUEUED, null, null,
                            null, null, ts));
                    notifications.add(new NewEvent("payment", p.uetr.toString(), "PaymentQueued",
                            p.validated.debtor().bic(), Map.of("uetr", p.uetr.toString(), "reason", reason)));
                    outcomes.put(p, TxOutcome.of(p.tx, PaymentState.QUEUED, null, null));
                }
                events.addAll(lc.events());
            }
            writer.applyPositionDeltas(book.deltas());
        }

        // 4. Persist states, history and outbox events.
        writer.finalizeStates(finals);
        payments.insertEvents(events);
        outbox.append(notifications);
        return outcomes;
    }

    private void reject(Pending p, Lifecycle lc, Rejection r, Map<Pending, TxOutcome> outcomes,
                        List<Lifecycle.Event> events, List<NewEvent> notifications,
                        List<PaymentBatchWriter.FinalState> finals) {
        lc.reject(r);
        events.addAll(lc.events());
        finals.add(new PaymentBatchWriter.FinalState(lc.paymentId(), PaymentState.REJECTED, r.code().name(),
                r.detail(), null, null, null));
        if (p.debtorId != null) {
            notifications.add(new NewEvent("payment", p.uetr.toString(), "PaymentRejected",
                    participants.byId(p.debtorId).bic(),
                    Map.of("uetr", p.uetr.toString(), "reasonCode", r.code().name(), "reason", r.detail())));
        }
        outcomes.put(p, TxOutcome.of(p.tx, PaymentState.REJECTED, r, null));
    }

    private static String quoteDetail(FxQuote q) {
        return "%s->%s settlement %s %s, quote %s expires %s".formatted(q.sourceCurrency(), q.targetCurrency(),
                q.settlementCurrency(),
                CurrencyRules.toMajor(q.settlementAmount(), q.settlementCurrency()).toPlainString(), q.id(),
                q.expiresAt());
    }

    private static PaymentBatchWriter.NewPayment newPayment(Pending p, Timestamp ts) {
        CreditTransfer t = p.tx;
        FxQuote q = p.quote;
        return new PaymentBatchWriter.NewPayment(p.uetr, p.messageId, p.hash, t.endToEndId(), t.txId(), t.instrId(),
                t.debtorAgent() == null ? "UNKNOWN" : t.debtorAgent(),
                t.creditorAgent() == null ? "UNKNOWN" : t.creditorAgent(), p.debtorId, p.creditorId,
                t.debtorName(), truncate(t.debtorAccount().value()), t.creditorName(),
                truncate(t.creditorAccount().value()), t.currency(), p.amountMinor,
                q == null ? null : q.targetCurrency(), q == null ? null : q.targetAmount(),
                q == null ? null : q.settlementAmount(), q == null ? null : q.id(),
                q == null ? null : q.sourceRate(), q == null ? null : q.targetRate(),
                q == null ? null : Timestamp.from(q.expiresAt()), ts);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 34 ? s : s.substring(0, 34);
    }

    static NewEvent acceptedEvent(CreditTransfer t, PaymentValidator.Validated v, FxQuote q, long cycleId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uetr", t.uetr());
        payload.put("endToEndId", t.endToEndId());
        payload.put("debtorAgent", v.debtor().bic());
        payload.put("creditorAgent", v.creditor().bic());
        payload.put("creditorAccount", t.creditorAccount().value());
        payload.put("creditorName", t.creditorName());
        payload.put("instructedCurrency", q.sourceCurrency());
        payload.put("instructedAmount", CurrencyRules.toMajor(q.sourceAmount(), q.sourceCurrency()).toPlainString());
        payload.put("creditCurrency", q.targetCurrency());
        payload.put("creditAmount", CurrencyRules.toMajor(q.targetAmount(), q.targetCurrency()).toPlainString());
        payload.put("cycleId", cycleId);
        // The creditor agent is told to credit the beneficiary immediately: settlement is guaranteed.
        return new NewEvent("payment", t.uetr(), "PaymentAccepted", v.creditor().bic(), payload);
    }

    /** Amount stored for audit even when the payment is rejected for an invalid amount. */
    private static long bestEffortMinor(CreditTransfer t) {
        if (t.amount() == null) {
            return 0;
        }
        int exp = CurrencyRules.isSupported(t.currency()) ? CurrencyRules.exponent(t.currency()) : 2;
        try {
            return t.amount().setScale(exp, RoundingMode.DOWN).movePointRight(exp).longValueExact();
        } catch (ArithmeticException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        running = true;
        sequencer = new Thread(this::loop, "payment-sequencer");
        sequencer.start();
    }

    @Override
    public void stop() {
        running = false;
        if (sequencer != null) {
            try {
                sequencer.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start before the web server accepts requests and stop after it has drained. */
    @Override
    public int getPhase() {
        return 0;
    }
}
