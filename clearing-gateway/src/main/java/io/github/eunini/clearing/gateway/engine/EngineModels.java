package io.github.eunini.clearing.gateway.engine;

import java.util.List;

/** JSON contract of the Rust netting engine (camelCase on the wire). */
public final class EngineModels {

    private EngineModels() {}

    public record Obligation(long id, String debtor, String creditor, String currency, long amount,
                             Long settlementAmount) {}

    public record FxRate(String currency, String rate, int exponent) {}

    public record NettingRequest(String cycleId, String settlementCurrency, int settlementExponent, String fxMode,
                                 List<FxRate> rates, List<Obligation> obligations) {}

    public record Position(String participant, long grossDebit, long grossCredit, long net) {}

    public record CurrencyPosition(String currency, String participant, long grossDebit, long grossCredit, long net) {}

    public record CurrencySummary(String currency, long obligationCount, long gross, long net) {}

    public record Transfer(String from, String to, long amount) {}

    public record NettingStats(long obligationCount, long participantCount, long currencyCount,
                               long grossSettlementValue, long netSettlementValue, long transferCount,
                               long transferLowerBound, boolean transfersOptimal, String planMethod,
                               double transferReductionPct, double liquiditySavingPct, long computeMicros) {}

    public record NettingResult(String cycleId, String settlementCurrency, String fxMode, List<Position> positions,
                                List<CurrencyPosition> currencyPositions, List<CurrencySummary> currencySummaries,
                                List<Transfer> transfers, NettingStats stats) {}

    public record Account(String participant, long balance, long limit) {}

    public record QueuedPayment(long id, String debtor, String creditor, long amount) {}

    public record LsmRequest(List<Account> accounts, List<QueuedPayment> queue) {}

    public record AccountBalance(String participant, long balance) {}

    public record LsmStats(long queued, long released, long releasedValue, long releasedByOffsetting, long removals,
                           long computeMicros) {}

    public record LsmResult(List<Long> released, List<Long> unresolved, List<AccountBalance> finalBalances,
                            LsmStats stats) {}
}
