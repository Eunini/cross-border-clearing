package io.github.eunini.clearing.gateway.web;

import io.github.eunini.clearing.gateway.fx.FxQuote;
import io.github.eunini.clearing.gateway.fx.FxRateService;
import io.github.eunini.clearing.gateway.validation.CurrencyRules;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Simulated FX rates and indicative quotes. */
@RestController
public class FxController {

    private final FxRateService fx;

    public FxController(FxRateService fx) {
        this.fx = fx;
    }

    public record RateView(BigDecimal mid, int spreadBps) {}

    @GetMapping("/api/fx/rates")
    public Map<String, RateView> rates() {
        Map<String, RateView> out = new TreeMap<>();
        fx.midRates().forEach((ccy, mid) -> out.put(ccy, new RateView(mid, fx.spreadBps(ccy))));
        return out;
    }

    public record QuoteRequest(String sourceCurrency, String targetCurrency, BigDecimal amount) {}

    @PostMapping("/api/fx/quotes")
    public Map<String, Object> quote(@RequestBody QuoteRequest req) {
        if (!CurrencyRules.isSupported(req.sourceCurrency()) || !CurrencyRules.isSupported(req.targetCurrency())) {
            throw new IllegalArgumentException("Unsupported currency");
        }
        long minor = CurrencyRules.toMinor(req.amount(), req.sourceCurrency())
                .orElseThrow(() -> new IllegalArgumentException("Invalid amount for " + req.sourceCurrency()));
        FxQuote q = fx.quote(req.sourceCurrency(), req.targetCurrency(), minor).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quoteId", q.id());
        out.put("source", Map.of("currency", q.sourceCurrency(),
                "amount", CurrencyRules.toMajor(q.sourceAmount(), q.sourceCurrency())));
        out.put("settlement", Map.of("currency", q.settlementCurrency(),
                "amount", CurrencyRules.toMajor(q.settlementAmount(), q.settlementCurrency())));
        out.put("target", Map.of("currency", q.targetCurrency(),
                "amount", CurrencyRules.toMajor(q.targetAmount(), q.targetCurrency())));
        out.put("sourceRate", q.sourceRate());
        out.put("targetRate", q.targetRate());
        out.put("expiresAt", q.expiresAt());
        out.put("indicative", true);
        return out;
    }
}
