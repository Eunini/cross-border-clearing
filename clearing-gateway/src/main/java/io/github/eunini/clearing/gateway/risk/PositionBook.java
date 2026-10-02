package io.github.eunini.clearing.gateway.risk;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory working copy of locked position rows used to decide a batch of
 * payments in arrival order. Pure logic, no I/O: the caller locks the rows,
 * runs every decision through the book, then writes {@link #deltas()} back.
 *
 * <p>Rules, applied per payment in order:
 * <ol>
 *   <li>FIFO: if the debtor already has queued payments, the new one queues too.</li>
 *   <li>Cap: the debtor's position after the debit must stay at or above
 *       {@code min(current position, -netDebitCap)}: never below the cap, and a
 *       participant already beyond its cap (e.g. after a cap reduction) may not
 *       get worse.</li>
 *   <li>Otherwise the amount moves from debtor to creditor immediately, so the
 *       creditor can use it for its own payments later in the same batch.</li>
 * </ol>
 */
public final class PositionBook {

    public enum Decision { ACCEPT, QUEUE_FIFO, QUEUE_CAP }

    private final Map<Integer, long[]> rows = new HashMap<>(); // position, queued, cap
    private final Map<Integer, long[]> initial = new HashMap<>();

    public PositionBook(Map<Integer, PositionRow> locked) {
        locked.forEach((id, r) -> {
            rows.put(id, new long[] {r.position(), r.queuedCount(), r.netDebitCap()});
            initial.put(id, new long[] {r.position(), r.queuedCount()});
        });
    }

    public Decision reserve(int debtorId, int creditorId, long amount) {
        long[] d = row(debtorId);
        long[] c = row(creditorId);
        if (d[1] > 0) {
            d[1]++;
            return Decision.QUEUE_FIFO;
        }
        long floor = Math.min(d[0], -d[2]);
        if (d[0] - amount < floor) {
            d[1]++;
            return Decision.QUEUE_CAP;
        }
        d[0] -= amount;
        c[0] += amount;
        return Decision.ACCEPT;
    }

    public long position(int participantId) {
        return row(participantId)[0];
    }

    public long queued(int participantId) {
        return row(participantId)[1];
    }

    public long cap(int participantId) {
        return row(participantId)[2];
    }

    /** Per participant: {position delta, queued-count delta}, only for rows that changed. */
    public Map<Integer, long[]> deltas() {
        Map<Integer, long[]> out = new HashMap<>();
        rows.forEach((id, r) -> {
            long[] i = initial.get(id);
            if (r[0] != i[0] || r[1] != i[1]) {
                out.put(id, new long[] {r[0] - i[0], r[1] - i[1]});
            }
        });
        return out;
    }

    private long[] row(int id) {
        long[] r = rows.get(id);
        if (r == null) {
            throw new IllegalArgumentException("Position row " + id + " is not locked in this book");
        }
        return r;
    }
}
