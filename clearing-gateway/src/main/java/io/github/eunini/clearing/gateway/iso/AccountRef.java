package io.github.eunini.clearing.gateway.iso;

/** Account identification from a pacs.008: either an IBAN or a scheme-less "other" id. */
public record AccountRef(String iban, String other) {

    public static AccountRef none() {
        return new AccountRef(null, null);
    }

    public boolean present() {
        return iban != null || other != null;
    }

    /** Display / storage form. */
    public String value() {
        return iban != null ? iban : other;
    }
}
