package io.github.eunini.clearing.gateway.iso;

/** Subset of the ISO 20022 ExternalStatusReason1Code list used by this rulebook. */
public enum ReasonCode {
    FF01("Invalid file format: message failed schema validation"),
    AM05("Duplication"),
    AM18("Number of transactions does not match NbOfTxs"),
    AM10("Control sum does not match"),
    RC01("Bank identifier incorrect"),
    RC03("Debtor agent is not a participant or does not match the instructing agent"),
    RC04("Creditor agent is not a participant"),
    AG01("Transaction forbidden: participant suspended"),
    AG03("Transaction not supported"),
    AC02("Invalid debtor account number"),
    AC03("Invalid creditor account number"),
    AM03("Currency not allowed"),
    AM12("Invalid amount"),
    AM02("Amount exceeds the per-payment limit"),
    DT01("Invalid settlement date"),
    CH21("Required element missing"),
    AB01("Clearing aborted: liquidity queue timeout");

    private final String description;

    ReasonCode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
