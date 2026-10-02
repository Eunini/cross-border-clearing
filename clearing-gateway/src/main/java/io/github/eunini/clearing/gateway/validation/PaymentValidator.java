package io.github.eunini.clearing.gateway.validation;

import io.github.eunini.clearing.gateway.iso.CreditTransfer;
import io.github.eunini.clearing.gateway.iso.ReasonCode;
import io.github.eunini.clearing.gateway.iso.Rejection;
import io.github.eunini.clearing.gateway.participant.Participant;
import io.github.eunini.clearing.gateway.participant.ParticipantDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Business validation of one schema-valid credit transfer against the
 * network rulebook. Checks are ordered so the most fundamental problem is
 * reported; the first failure wins.
 */
@Component
public class PaymentValidator {

    /** Validated view: the resolved participants and the amount in minor units. */
    public record Validated(Participant debtor, Participant creditor, long amountMinor) {}

    private final ParticipantDirectory participants;
    private final Clock clock;

    public PaymentValidator(ParticipantDirectory participants, Clock clock) {
        this.participants = participants;
        this.clock = clock;
    }

    public sealed interface Result permits Ok, Failed {}

    public record Ok(Validated value) implements Result {}

    public record Failed(Rejection rejection) implements Result {}

    public Result validate(CreditTransfer tx) {
        // Agents
        if (tx.debtorAgent() == null || !BicValidator.isValid(tx.debtorAgent())
                || tx.creditorAgent() == null || !BicValidator.isValid(tx.creditorAgent())) {
            return fail(ReasonCode.RC01, "Agent BICFI missing or not a valid ISO 9362 BIC");
        }
        if (tx.instructingAgent() == null
                || !BicValidator.bic8(tx.instructingAgent()).equals(BicValidator.bic8(tx.debtorAgent()))) {
            return fail(ReasonCode.RC03, "GrpHdr/InstgAgt must be present and equal to DbtrAgt");
        }
        Optional<Participant> debtor = participants.byBic(tx.debtorAgent());
        if (debtor.isEmpty()) {
            return fail(ReasonCode.RC03, "Debtor agent " + tx.debtorAgent() + " is not a participant");
        }
        Optional<Participant> creditor = participants.byBic(tx.creditorAgent());
        if (creditor.isEmpty()) {
            return fail(ReasonCode.RC04, "Creditor agent " + tx.creditorAgent() + " is not a participant");
        }
        if (!debtor.get().active() || !creditor.get().active()) {
            return fail(ReasonCode.AG01, "Participant suspended");
        }
        if (debtor.get().id() == creditor.get().id()) {
            return fail(ReasonCode.AG03, "On-us payments are not cleared by the network");
        }
        if (tx.instructedAgent() != null && participants.byBic(tx.instructedAgent()).isPresent()
                && !BicValidator.bic8(tx.instructedAgent()).equals(BicValidator.bic8(tx.creditorAgent()))) {
            return fail(ReasonCode.RC04, "GrpHdr/InstdAgt, when a participant, must equal CdtrAgt");
        }
        if (!"CLRG".equals(tx.settlementMethod())) {
            return fail(ReasonCode.AG03, "SttlmMtd must be CLRG");
        }
        // Identification
        if (tx.uetr() == null) {
            return fail(ReasonCode.CH21, "PmtId/UETR is mandatory in this network");
        }
        // Currency and amount
        if (!CurrencyRules.isSupported(tx.currency())) {
            return fail(ReasonCode.AM03, "Currency " + tx.currency() + " is not supported");
        }
        if (!tx.currency().equals(debtor.get().currency())) {
            return fail(ReasonCode.AM03, "IntrBkSttlmAmt must be in the debtor agent's currency "
                    + debtor.get().currency());
        }
        Optional<Long> minor = CurrencyRules.toMinor(tx.amount(), tx.currency());
        if (minor.isEmpty()) {
            return fail(ReasonCode.AM12, "Amount must be positive with at most "
                    + CurrencyRules.exponent(tx.currency()) + " decimals for " + tx.currency());
        }
        // Accounts
        if (!AccountRules.isValid(tx.debtorAccount(), debtor.get().country(), debtor.get().bic())) {
            return fail(ReasonCode.AC02, "DbtrAcct is not a valid " + debtor.get().country() + " account");
        }
        if (!AccountRules.isValid(tx.creditorAccount(), creditor.get().country(), creditor.get().bic())) {
            return fail(ReasonCode.AC03, "CdtrAcct is not a valid " + creditor.get().country() + " account");
        }
        // Dates: instant payments settle today (UTC business day).
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (tx.settlementDate() == null || !tx.settlementDate().equals(today)) {
            return fail(ReasonCode.DT01, "IntrBkSttlmDt must be the current business date " + today);
        }
        return new Ok(new Validated(debtor.get(), creditor.get(), minor.get()));
    }

    private static Failed fail(ReasonCode code, String detail) {
        return new Failed(Rejection.of(code, detail));
    }
}
