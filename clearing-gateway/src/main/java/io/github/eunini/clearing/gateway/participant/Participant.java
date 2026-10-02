package io.github.eunini.clearing.gateway.participant;

/** A (simulated) participant bank. {@code netDebitCap} is in settlement-currency minor units. */
public record Participant(int id, String bic, String name, String country, String currency,
                          long netDebitCap, boolean active) {}
