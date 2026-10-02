package io.github.eunini.clearing.gateway.iso;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * One CdtTrfTxInf of a pacs.008, flattened to the fields the clearing rulebook
 * uses. Group-header fields that apply to every transaction (MsgId, InstgAgt,
 * settlement method) are copied in so a transaction can be processed on its own.
 */
public record CreditTransfer(
        String msgId,
        String instructingAgent,
        String instructedAgent,
        String settlementMethod,
        String uetr,
        String endToEndId,
        String txId,
        String instrId,
        String debtorAgent,
        String creditorAgent,
        String debtorName,
        AccountRef debtorAccount,
        String creditorName,
        AccountRef creditorAccount,
        String currency,
        BigDecimal amount,
        LocalDate settlementDate) {

    /**
     * Canonical SHA-256 over the business content. A retransmission with the
     * same UETR must hash identically; a different payment reusing a UETR will not.
     */
    public String contentHash() {
        String canonical = String.join("|",
                nz(uetr), nz(endToEndId), nz(txId), nz(instrId), nz(debtorAgent), nz(creditorAgent),
                nz(debtorName), nz(debtorAccount.value()), nz(creditorName), nz(creditorAccount.value()),
                nz(currency), amount == null ? "" : amount.stripTrailingZeros().toPlainString(),
                settlementDate == null ? "" : settlementDate.toString());
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
