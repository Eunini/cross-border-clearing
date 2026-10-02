package io.github.eunini.clearing.gateway.iso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eunini.clearing.gateway.payment.PaymentState;
import io.github.eunini.clearing.gateway.payment.TxOutcome;
import io.github.eunini.clearing.iso.sample.Pacs008Writer;
import io.github.eunini.clearing.iso.sample.SampleAccounts;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Iso20022CodecTest {

    final Iso20022Codec codec = new Iso20022Codec();
    final SplittableRandom rng = new SplittableRandom(5);

    String sample(int txs) {
        List<Pacs008Writer.Tx> list = java.util.stream.IntStream.range(0, txs).mapToObj(i ->
                new Pacs008Writer.Tx(UUID.randomUUID().toString(), "E2E-" + i, "TX-" + i, "XB01USNY", "XB06GB2L",
                        "Ada", SampleAccounts.forParticipant("US", "XB01USNY", rng), "Bob",
                        SampleAccounts.forParticipant("GB", "XB06GB2L", rng), "USD", new BigDecimal("10.5" + i)))
                .toList();
        return Pacs008Writer.write(new Pacs008Writer.Message("MSG-1", Instant.parse("2026-10-02T10:00:00Z"),
                "XB01USNY", "XB06GB2L", LocalDate.parse("2026-10-02"), true, list));
    }

    @Test
    void parsesAndMapsValidMessage() {
        var parsed = codec.parsePacs008(sample(3).getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.errors()).isEmpty();
        var group = Pacs008Mapper.map(parsed.document());
        assertThat(group.msgId()).isEqualTo("MSG-1");
        assertThat(group.nbOfTxs()).isEqualTo("3");
        assertThat(group.transactions()).hasSize(3);
        CreditTransfer t = group.transactions().getFirst();
        assertThat(t.settlementMethod()).isEqualTo("CLRG");
        assertThat(t.instructingAgent()).isEqualTo("XB01USNY");
        assertThat(t.settlementDate()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(t.creditorAccount().iban()).startsWith("GB");
        assertThat(t.contentHash()).hasSize(64).isEqualTo(group.transactions().getFirst().contentHash());
        assertThat(t.contentHash()).isNotEqualTo(group.transactions().get(1).contentHash());
    }

    @Test
    void reportsSchemaViolations() {
        String bad = sample(1).replace("<Ccy>", "<Ccy>").replace("Ccy=\"USD\"", "Ccy=\"usd\"");
        var parsed = codec.parsePacs008(bad.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.errors()).isNotEmpty();
    }

    @Test
    void rejectsWrongMessageVersionAndGarbage() {
        String v9 = sample(1).replace("pacs.008.001.08", "pacs.008.001.09");
        assertThat(codec.parsePacs008(v9.getBytes(StandardCharsets.UTF_8)).valid()).isFalse();
        assertThat(codec.parsePacs008("not xml".getBytes(StandardCharsets.UTF_8)).valid()).isFalse();
    }

    @Test
    void producesSchemaValidPacs002() {
        var outcomes = List.of(
                new TxOutcome(UUID.randomUUID().toString(), "E1", "T1", null, PaymentState.ACCEPTED, null, Instant.now(), false),
                new TxOutcome(UUID.randomUUID().toString(), "E2", "T2", null, PaymentState.REJECTED,
                        Rejection.of(ReasonCode.AC03, "bad"), null, false));
        String xml = codec.writePacs002(Pacs002Builder.transactions("MSG-1", null, outcomes, Instant.now()));
        assertThat(xml).contains("<GrpSts>PART</GrpSts>").contains("<Cd>AC03</Cd>").contains("pacs.002.001.10");
    }

    @Test
    void refusesToEmitSchemaInvalidPacs002() {
        var outcome = new TxOutcome(null, "E".repeat(36), null, null, PaymentState.ACCEPTED, null, null, false);
        assertThatThrownBy(() -> codec.writePacs002(Pacs002Builder.transactions("M", null, List.of(outcome), Instant.now())))
                .isInstanceOf(IllegalStateException.class);
    }
}
