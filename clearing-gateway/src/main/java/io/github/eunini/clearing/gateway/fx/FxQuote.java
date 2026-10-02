package io.github.eunini.clearing.gateway.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A firm quote converting {@code sourceAmount} (minor units of the source
 * currency) into the settlement currency and on into the target currency.
 *
 * @param sourceRate units of source currency per settlement unit applied (mid widened by half the spread)
 * @param targetRate units of target currency per settlement unit applied (mid narrowed by half the spread)
 */
public record FxQuote(UUID id, String sourceCurrency, String targetCurrency, String settlementCurrency,
                      long sourceAmount, long settlementAmount, long targetAmount,
                      BigDecimal sourceRate, BigDecimal targetRate, Instant createdAt, Instant expiresAt) {}
