package io.github.eunini.clearing.gateway.payment;

import java.time.Instant;
import java.util.UUID;

/** Payment row projection used for idempotent replays and the tracker. */
public record StoredPayment(long id, UUID uetr, String txSha256, String endToEndId, String txId, String instrId,
                            String debtorAgent, String creditorAgent, String currency, long amount,
                            String targetCurrency, Long targetAmount, Long settlementAmount,
                            PaymentState state, String reasonCode, String reasonText, Long cycleId,
                            Instant receivedAt, Instant acceptedAt) {}
