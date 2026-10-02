package io.github.eunini.clearing.gateway.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Payment lifecycle.
 *
 * <pre>
 * RECEIVED -> VALIDATED -> FX_QUOTED -> ACCEPTED -> CLEARED -> SETTLED
 *                               \-> QUEUED --/
 * RECEIVED | VALIDATED | FX_QUOTED | QUEUED -> REJECTED
 * </pre>
 *
 * ACCEPTED means the pre-settlement risk check passed and the debtor agent's
 * position was debited (the payment is irrevocable and settlement is
 * guaranteed); CLEARED means it was included in a netted clearing cycle;
 * SETTLED means the cycle's settlement transfers were confirmed.
 */
public enum PaymentState {
    RECEIVED, VALIDATED, FX_QUOTED, QUEUED, ACCEPTED, CLEARED, SETTLED, REJECTED;

    private Set<PaymentState> next;

    static {
        RECEIVED.next = EnumSet.of(VALIDATED, REJECTED);
        VALIDATED.next = EnumSet.of(FX_QUOTED, REJECTED);
        FX_QUOTED.next = EnumSet.of(ACCEPTED, QUEUED, REJECTED);
        QUEUED.next = EnumSet.of(ACCEPTED, REJECTED);
        ACCEPTED.next = EnumSet.of(CLEARED);
        CLEARED.next = EnumSet.of(SETTLED);
        SETTLED.next = EnumSet.noneOf(PaymentState.class);
        REJECTED.next = EnumSet.noneOf(PaymentState.class);
    }

    public boolean canMoveTo(PaymentState target) {
        return next.contains(target);
    }

    public boolean isTerminal() {
        return next.isEmpty();
    }

    /** ISO 20022 ExternalPaymentTransactionStatus1Code reported for this state. */
    public String isoStatus() {
        return switch (this) {
            case RECEIVED, VALIDATED, FX_QUOTED, QUEUED -> "PDNG";
            case ACCEPTED, CLEARED -> "ACSP";
            case SETTLED -> "ACSC";
            case REJECTED -> "RJCT";
        };
    }
}
