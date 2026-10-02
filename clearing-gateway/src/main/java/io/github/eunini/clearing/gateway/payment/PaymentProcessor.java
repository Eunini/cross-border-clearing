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
import io.github.eunini.clearing.gateway.risk.RiskDecision;
import io.github.eunini.clearing.gateway.risk.RiskService;
import io.github.eunini.clearing.gateway.validation.CurrencyRules;
import io.github.eunini.clearing.gateway.validation.PaymentValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes one credit transfer end to end inside a single database
 * transaction: claim UETR, validate, detect EndToEndId duplicates, quote FX,
 * run the risk check, persist the outcome, its history and its outbox events.
 *
 * <p><b>Exactly-once.</b> The UETR is claimed first with
 * {@code INSERT ... ON CONFLICT DO NOTHING}. If it is already taken and the
 * content hash matches, the stored outcome is returned without any side
 * effects (a retransmission); if the content differs, the transaction is
 * rejected AM05 without touching the existing payment. Because every side
 * effect (position change, history, outbox) commits atomically with the claim,
 * a crash at any point either leaves no trace or a complete result.
 */
@Service
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private static final int MAX_ATTEMPTS = 3;

    private final PaymentRepository payments;
    private final PaymentValidator validator;
    private final ParticipantDirectory participants;
    private final FxRateService fx;
    private final RiskService risk;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final ClearingProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentProcessor(PaymentRepository payments, PaymentValidator validator, ParticipantDirectory participants,
                            FxRateService fx, RiskService risk, OutboxRepository outbox, TransactionTemplate tx,
                            ClearingProperties props, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.validator = validator;
        this.participants = participants;
        this.fx = fx;
        this.risk = risk;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    public TxOutcome process(CreditTransfer transfer, Long messageId) {
        Timer.Sample sample = Timer.start(meters);
        TxOutcome outcome = processWithRetry(transfer, messageId);
        sample.stop(meters.timer("clearing.payment.processing", "status", outcome.state().isoStatus(),
                "replay", Boolean.toString(outcome.replay())));
        return outcome;
    }

    private TxOutcome processWithRetry(CreditTransfer transfer, Long messageId) {
        UUID uetr;
        try {
            uetr = transfer.uetr() == null ? null : UUID.fromString(transfer.uetr());
        } catch (IllegalArgumentException e) {
            uetr = null;
        }
        if (uetr == null) {
            return TxOutcome.untracked(transfer, Rejection.of(ReasonCode.CH21, "PmtId/UETR is mandatory"));
        }
        for (int attempt = 1; ; attempt++) {
            try {
                final UUID id = uetr;
                return tx.execute(status -> processInTransaction(transfer, id, messageId));
            } catch (TransientDataAccessException e) { // deadlock, lock or serialization failure
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.debug("Retrying payment {} after transient failure: {}", uetr, e.getMessage());
            }
        }
    }

    private TxOutcome processInTransaction(CreditTransfer t, UUID uetr, Long messageId) {
        String hash = t.contentHash();
        Integer debtorId = participants.byBic(t.debtorAgent()).map(Participant::id).orElse(null);
        Integer creditorId = participants.byBic(t.creditorAgent()).map(Participant::id).orElse(null);
        Optional<Long> claimed = payments.insertReceived(uetr, messageId, hash, t, debtorId, creditorId,
                bestEffortMinor(t));
        if (claimed.isEmpty()) {
            StoredPayment existing = payments.findByUetr(uetr).orElseThrow();
            if (existing.txSha256().equals(hash)) {
                return TxOutcome.replay(existing);
            }
            return TxOutcome.untracked(t, Rejection.of(ReasonCode.AM05,
                    "UETR " + uetr + " already used by a different payment"));
        }
        long id = claimed.get();
        Lifecycle lc = Lifecycle.received(id);

        PaymentValidator.Result result = validator.validate(t);
        if (result instanceof PaymentValidator.Failed f) {
            return reject(t, lc, f.rejection());
        }
        PaymentValidator.Validated v = ((PaymentValidator.Ok) result).value();
        lc.to(PaymentState.VALIDATED, null);

        FxQuote quote = fx.quote(t.currency(), v.creditor().currency(), v.amountMinor()).orElseThrow();
        payments.recordQuote(id, quote);
        lc.to(PaymentState.FX_QUOTED, "%s->%s settlement %s %s, quote %s expires %s".formatted(
                quote.sourceCurrency(), quote.targetCurrency(), quote.settlementCurrency(),
                CurrencyRules.toMajor(quote.settlementAmount(), quote.settlementCurrency()).toPlainString(),
                quote.id(), quote.expiresAt()));
        if (quote.settlementAmount() > props.maxSettlementAmountMinor()) {
            return reject(t, lc, Rejection.of(ReasonCode.AM02, "Settlement value exceeds the per-payment limit"));
        }
        if (quote.settlementAmount() <= 0 || quote.targetAmount() <= 0) {
            return reject(t, lc, Rejection.of(ReasonCode.AM12, "Amount too small to convert"));
        }

        // Claimed last, so only payments that can still succeed hold an EndToEndId
        // (a rejected payment can be corrected and resent with the same reference).
        if (!payments.claimEndToEnd(v.debtor().bic(), t.endToEndId(), uetr)) {
            return reject(t, lc, Rejection.of(ReasonCode.AM05,
                    "EndToEndId " + t.endToEndId() + " already used by " + v.debtor().bic()));
        }

        Instant now = clock.instant();
        RiskDecision decision = risk.reserve(v.debtor().id(), v.creditor().id(), quote.settlementAmount());
        if (decision.accepted()) {
            lc.to(PaymentState.ACCEPTED, "cycle " + decision.cycleId());
            payments.markAccepted(id, decision.cycleId(), now);
            outbox.append(acceptedEvent(t, v, quote, decision.cycleId()));
        } else {
            lc.to(PaymentState.QUEUED, decision.reason());
            payments.markQueued(id, now);
            outbox.append(new NewEvent("payment", uetr.toString(), "PaymentQueued", v.debtor().bic(),
                    Map.of("uetr", uetr.toString(), "reason", decision.reason())));
        }
        payments.insertEvents(lc.events());
        return TxOutcome.of(t, lc.state(), null, decision.accepted() ? now : null);
    }

    private TxOutcome reject(CreditTransfer t, Lifecycle lc, Rejection r) {
        lc.reject(r);
        payments.markRejected(lc.paymentId(), r);
        payments.insertEvents(lc.events());
        if (t.debtorAgent() != null && participants.byBic(t.debtorAgent()).isPresent()) {
            outbox.append(new NewEvent("payment", t.uetr(), "PaymentRejected", t.debtorAgent().substring(0, 8),
                    Map.of("uetr", t.uetr(), "reasonCode", r.code().name(), "reason", r.detail())));
        }
        return TxOutcome.of(t, PaymentState.REJECTED, r, null);
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
}
