package io.github.eunini.clearing.gateway.web;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.payment.PaymentRepository;
import io.github.eunini.clearing.gateway.payment.StoredPayment;
import io.github.eunini.clearing.gateway.validation.CurrencyRules;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** gpi-style tracker: current status plus the full, timestamped state history of a payment. */
@RestController
public class TrackerController {

    public record Amount(String currency, String value) {}

    public record Status(String uetr, String state, String isoStatus, String reasonCode, String reasonText,
                         String debtorAgent, String creditorAgent, String endToEndId, Amount instructed,
                         Amount credited, Amount settlementValue, Long cycleId, Instant receivedAt,
                         Instant acceptedAt, List<PaymentRepository.HistoryEntry> history) {}

    private final PaymentRepository payments;
    private final ClearingProperties props;

    public TrackerController(PaymentRepository payments, ClearingProperties props) {
        this.payments = payments;
        this.props = props;
    }

    @GetMapping("/payments/{uetr}/status")
    public Status status(@PathVariable UUID uetr) {
        StoredPayment p = payments.findByUetr(uetr)
                .orElseThrow(() -> new NoSuchElementException("Unknown UETR " + uetr));
        return new Status(p.uetr().toString(), p.state().name(), p.state().isoStatus(), p.reasonCode(),
                p.reasonText(), p.debtorAgent(), p.creditorAgent(), p.endToEndId(),
                amount(p.currency(), p.amount()), amount(p.targetCurrency(), p.targetAmount()),
                amount(props.settlementCurrency(), p.settlementAmount()), p.cycleId(), p.receivedAt(), p.acceptedAt(),
                payments.history(p.id()));
    }

    private static Amount amount(String ccy, Long minor) {
        if (ccy == null || minor == null || !CurrencyRules.isSupported(ccy)) {
            return null;
        }
        return new Amount(ccy, CurrencyRules.toMajor(minor, ccy).toPlainString());
    }
}
