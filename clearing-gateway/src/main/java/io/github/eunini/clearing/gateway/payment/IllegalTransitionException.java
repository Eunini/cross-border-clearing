package io.github.eunini.clearing.gateway.payment;

public class IllegalTransitionException extends IllegalStateException {
    public IllegalTransitionException(PaymentState from, PaymentState to) {
        super("Illegal payment state transition " + from + " -> " + to);
    }
}
