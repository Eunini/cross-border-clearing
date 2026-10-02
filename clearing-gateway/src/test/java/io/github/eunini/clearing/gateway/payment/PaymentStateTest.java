package io.github.eunini.clearing.gateway.payment;

import static io.github.eunini.clearing.gateway.payment.PaymentState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eunini.clearing.gateway.iso.ReasonCode;
import io.github.eunini.clearing.gateway.iso.Rejection;
import org.junit.jupiter.api.Test;

class PaymentStateTest {

    @Test
    void happyPathAndQueuedPathAreAllowed() {
        Lifecycle lc = Lifecycle.received(1).to(VALIDATED, null).to(FX_QUOTED, null).to(QUEUED, "cap")
                .to(ACCEPTED, null);
        assertThat(lc.state()).isEqualTo(ACCEPTED);
        assertThat(lc.events()).extracting(Lifecycle.Event::to)
                .containsExactly(RECEIVED, VALIDATED, FX_QUOTED, QUEUED, ACCEPTED);
        assertThat(ACCEPTED.canMoveTo(CLEARED)).isTrue();
        assertThat(CLEARED.canMoveTo(SETTLED)).isTrue();
    }

    @Test
    void illegalTransitionsAreRefused() {
        assertThatThrownBy(() -> Lifecycle.received(1).to(ACCEPTED, null)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> Lifecycle.resume(1, ACCEPTED).reject(Rejection.of(ReasonCode.AM05, "x")))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(SETTLED.isTerminal()).isTrue();
        assertThat(REJECTED.isTerminal()).isTrue();
        assertThat(SETTLED.canMoveTo(REJECTED)).isFalse();
    }

    @Test
    void rejectionRecordsReasonCode() {
        Lifecycle lc = Lifecycle.received(9).to(VALIDATED, null).reject(Rejection.of(ReasonCode.AC03, "bad account"));
        assertThat(lc.events().getLast().reasonCode()).isEqualTo("AC03");
        assertThat(lc.state().isoStatus()).isEqualTo("RJCT");
    }

    @Test
    void isoStatusMapping() {
        assertThat(QUEUED.isoStatus()).isEqualTo("PDNG");
        assertThat(ACCEPTED.isoStatus()).isEqualTo("ACSP");
        assertThat(CLEARED.isoStatus()).isEqualTo("ACSP");
        assertThat(SETTLED.isoStatus()).isEqualTo("ACSC");
    }
}
