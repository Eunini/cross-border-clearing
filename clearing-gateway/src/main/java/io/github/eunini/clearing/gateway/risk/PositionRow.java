package io.github.eunini.clearing.gateway.risk;

/** A participant's locked position row joined with its cap. */
public record PositionRow(int participantId, long position, int queuedCount, long netDebitCap) {

    /** Lowest position the participant may reach: never below -cap, but an already-breached position may not worsen. */
    public long floor() {
        return Math.min(position, -netDebitCap);
    }
}
