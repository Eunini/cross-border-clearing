package io.github.eunini.clearing.gateway.cycle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Clearing cycle row, amounts in settlement-currency minor units. */
public record CycleSummary(long id, LocalDate businessDate, String status, Instant openedAt, Instant closedAt,
                           Instant nettedAt, Instant settledAt, Integer paymentCount, Long grossValue,
                           Long netValue, Integer transferCount, Integer transferLowerBound,
                           Boolean transfersOptimal, String planMethod, Long nettingMicros,
                           Long nettingRoundTripMs, BigDecimal liquiditySavingPct,
                           BigDecimal transferReductionPct, String failureReason) {}
