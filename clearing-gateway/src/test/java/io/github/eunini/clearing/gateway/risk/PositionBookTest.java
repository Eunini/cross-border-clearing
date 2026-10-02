package io.github.eunini.clearing.gateway.risk;

import static io.github.eunini.clearing.gateway.risk.PositionBook.Decision.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PositionBookTest {

    static PositionBook book(PositionRow... rows) {
        Map<Integer, PositionRow> m = new java.util.HashMap<>();
        for (PositionRow r : rows) {
            m.put(r.participantId(), r);
        }
        return new PositionBook(m);
    }

    @Test
    void acceptsUpToTheCapAndQueuesBeyondIt() {
        PositionBook b = book(new PositionRow(1, 0, 0, 100), new PositionRow(2, 0, 0, 0));
        assertThat(b.reserve(1, 2, 60)).isEqualTo(ACCEPT);
        assertThat(b.reserve(1, 2, 40)).isEqualTo(ACCEPT);
        assertThat(b.position(1)).isEqualTo(-100);
        assertThat(b.reserve(1, 2, 1)).isEqualTo(QUEUE_CAP);
        assertThat(b.queued(1)).isEqualTo(1);
    }

    @Test
    void queuedDebtorQueuesEverythingAfterwards() {
        PositionBook b = book(new PositionRow(1, 0, 0, 100), new PositionRow(2, 0, 0, 0));
        assertThat(b.reserve(1, 2, 500)).isEqualTo(QUEUE_CAP);
        assertThat(b.reserve(1, 2, 1)).isEqualTo(QUEUE_FIFO);
        assertThat(book(new PositionRow(1, 0, 3, 100), new PositionRow(2, 0, 0, 0)).reserve(1, 2, 1))
                .isEqualTo(QUEUE_FIFO);
    }

    @Test
    void incomingFundsWithinTheBatchCreateHeadroom() {
        PositionBook b = book(new PositionRow(1, 0, 0, 0), new PositionRow(2, 0, 0, 1_000));
        assertThat(b.reserve(2, 1, 300)).isEqualTo(ACCEPT); // 1 receives 300
        assertThat(b.reserve(1, 2, 300)).isEqualTo(ACCEPT); // and can pass it on
        assertThat(b.deltas()).isEmpty(); // net zero for both
    }

    @Test
    void breachedParticipantMayNotWorsen() {
        PositionBook b = book(new PositionRow(1, -500, 0, 100), new PositionRow(2, 0, 0, 0));
        assertThat(b.reserve(1, 2, 1)).isEqualTo(QUEUE_CAP);
    }

    @Test
    void deltasReportOnlyChangedRows() {
        PositionBook b = book(new PositionRow(1, 0, 0, 100), new PositionRow(2, 0, 0, 0), new PositionRow(3, 5, 0, 0));
        b.reserve(1, 2, 70);
        b.reserve(1, 2, 70);
        Map<Integer, long[]> d = b.deltas();
        assertThat(d).containsOnlyKeys(1, 2);
        assertThat(d.get(1)).containsExactly(-70, 1);
        assertThat(d.get(2)).containsExactly(70, 0);
        assertThatThrownBy(() -> b.reserve(9, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
