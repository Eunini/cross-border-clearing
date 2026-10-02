package io.github.eunini.clearing.gateway.risk;

/** Outcome of the pre-settlement risk check. */
public record RiskDecision(boolean accepted, long cycleId, String reason) {

    public static RiskDecision accept(long cycleId) {
        return new RiskDecision(true, cycleId, null);
    }

    public static RiskDecision queue(String reason) {
        return new RiskDecision(false, -1, reason);
    }
}
