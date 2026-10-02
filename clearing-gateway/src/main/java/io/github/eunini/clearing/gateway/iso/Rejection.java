package io.github.eunini.clearing.gateway.iso;

/** A business rejection: ISO reason code plus a short (Max105Text) explanation. */
public record Rejection(ReasonCode code, String detail) {

    public Rejection {
        if (detail != null && detail.length() > 105) {
            detail = detail.substring(0, 105);
        }
    }

    public static Rejection of(ReasonCode code, String detail) {
        return new Rejection(code, detail);
    }
}
