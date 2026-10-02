package io.github.eunini.clearing.gateway.payment;

import io.github.eunini.clearing.gateway.iso.Rejection;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the transitions of one payment inside a single database transaction
 * and enforces the state machine. Events are flushed in one batch at the end.
 */
public final class Lifecycle {

    public record Event(long paymentId, PaymentState from, PaymentState to, String reasonCode, String detail) {}

    private final long paymentId;
    private PaymentState state;
    private final List<Event> events = new ArrayList<>(6);

    private Lifecycle(long paymentId, PaymentState initial, boolean recordInitial) {
        this.paymentId = paymentId;
        this.state = initial;
        if (recordInitial) {
            events.add(new Event(paymentId, null, initial, null, null));
        }
    }

    /** A new payment: records the initial RECEIVED event. */
    public static Lifecycle received(long paymentId) {
        return new Lifecycle(paymentId, PaymentState.RECEIVED, true);
    }

    /** An existing payment loaded in {@code current} state. */
    public static Lifecycle resume(long paymentId, PaymentState current) {
        return new Lifecycle(paymentId, current, false);
    }

    public Lifecycle to(PaymentState target, String detail) {
        if (!state.canMoveTo(target)) {
            throw new IllegalTransitionException(state, target);
        }
        events.add(new Event(paymentId, state, target, null, detail));
        state = target;
        return this;
    }

    public Lifecycle reject(Rejection r) {
        if (!state.canMoveTo(PaymentState.REJECTED)) {
            throw new IllegalTransitionException(state, PaymentState.REJECTED);
        }
        events.add(new Event(paymentId, state, PaymentState.REJECTED, r.code().name(), r.detail()));
        state = PaymentState.REJECTED;
        return this;
    }

    public PaymentState state() {
        return state;
    }

    public long paymentId() {
        return paymentId;
    }

    public List<Event> events() {
        return events;
    }
}
