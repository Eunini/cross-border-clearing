package io.github.eunini.clearing.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rulebook and runtime settings of the clearing network.
 *
 * @param settlementCurrency        currency in which net positions are settled
 * @param quoteTtl                  validity of an FX quote; queued payments whose quote expires are rejected (AB01)
 * @param maxTransactionsPerMessage upper bound on CdtTrfTxInf per pacs.008
 * @param maxMessageBytes           upper bound on the request body
 * @param maxSettlementAmountMinor  per-payment limit in settlement-currency minor units (AM02 above it)
 */
@ConfigurationProperties("clearing")
public record ClearingProperties(
        @DefaultValue("USD") String settlementCurrency,
        @DefaultValue("30s") Duration quoteTtl,
        @DefaultValue("500") int maxTransactionsPerMessage,
        @DefaultValue("10485760") long maxMessageBytes,
        @DefaultValue("100000000") long maxSettlementAmountMinor,
        @DefaultValue Scheduling scheduling,
        @DefaultValue Engine engine,
        @DefaultValue Fx fx) {

    /**
     * @param enabled          master switch for background jobs (disabled in tests)
     * @param cycleInterval    automatic cycle cut-off period; zero disables automatic cut-off
     * @param lsmInterval      how often queued payments are offered to the liquidity-saving mechanism
     * @param outboxInterval   outbox polling period
     * @param outboxBatchSize  events published per poll
     */
    public record Scheduling(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") Duration cycleInterval,
            @DefaultValue("500ms") Duration lsmInterval,
            @DefaultValue("200ms") Duration outboxInterval,
            @DefaultValue("1000") int outboxBatchSize) {}

    /**
     * @param baseUrl        netting engine base URL
     * @param connectTimeout TCP connect timeout
     * @param readTimeout    response timeout (large cycles take a few seconds to transfer)
     * @param maxAttempts    attempts per call; calls are pure functions of their input so retrying is safe
     */
    public record Engine(
            @DefaultValue("http://localhost:7070") String baseUrl,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout,
            @DefaultValue("3") int maxAttempts) {}

    /**
     * @param seed          seed of the simulated rate random walk (reproducible runs)
     * @param tick          how often simulated mid rates move
     * @param volatilityBps standard deviation of one tick's move, in basis points
     */
    public record Fx(
            @DefaultValue("42") long seed,
            @DefaultValue("1s") Duration tick,
            @DefaultValue("2.0") double volatilityBps) {}
}
