package io.github.eunini.clearing.gateway.payment;

import io.github.eunini.clearing.gateway.iso.CreditTransfer;
import io.github.eunini.clearing.gateway.iso.Rejection;
import java.time.Instant;

/**
 * Result of processing one credit transfer, enough to build its pacs.002
 * TxInfAndSts entry.
 *
 * @param replay true when the UETR had already been processed and the stored outcome was returned
 */
public record TxOutcome(String uetr, String endToEndId, String txId, String instrId, PaymentState state,
                        Rejection rejection, Instant acceptedAt, boolean replay) {

    public static TxOutcome of(CreditTransfer tx, PaymentState state, Rejection rejection, Instant acceptedAt) {
        return new TxOutcome(tx.uetr(), tx.endToEndId(), tx.txId(), tx.instrId(), state, rejection, acceptedAt, false);
    }

    /** A rejection that is reported but not persisted as a payment (no usable UETR, or UETR owned by another payment). */
    public static TxOutcome untracked(CreditTransfer tx, Rejection rejection) {
        return of(tx, PaymentState.REJECTED, rejection, null);
    }

    public static TxOutcome replay(StoredPayment p) {
        Rejection r = p.reasonCode() == null ? null
                : Rejection.of(io.github.eunini.clearing.gateway.iso.ReasonCode.valueOf(p.reasonCode()), p.reasonText());
        return new TxOutcome(p.uetr().toString(), p.endToEndId(), p.txId(), p.instrId(), p.state(), r,
                p.acceptedAt(), true);
    }
}
