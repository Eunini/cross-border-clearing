package io.github.eunini.clearing.gateway.it;

import static io.github.eunini.clearing.gateway.support.Messages.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.clearing.gateway.support.IntegrationTestBase;
import io.github.eunini.clearing.iso.sample.Pacs008Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class GatewayIT extends IntegrationTestBase {

    private String submit(String xml) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> r = http.postForEntity("/iso20022/pacs.008", new HttpEntity<>(xml, h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private long paymentCount() {
        return jdbc.sql("select count(*) from payment").query(Long.class).single();
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsValidPaymentAndExposesTrackerHistory() {
        Pacs008Writer.Tx tx = tx(US1, IN1, "1500.00");
        String report = submit(message(US1, tx));

        assertThat(groupStatus(report)).isEqualTo("ACSP");
        assertThat(txStatuses(report)).containsExactly("ACSP");
        assertThat(xpath(report, "//*[local-name()='OrgnlUETR']")).containsExactly(tx.uetr());
        // USD has no spread, so the settlement value equals the instructed amount.
        assertThat(position(US1.bic())).isEqualTo(-150_000);
        assertThat(position(IN1.bic())).isEqualTo(150_000);

        Map<String, Object> status = http.getForObject("/payments/" + tx.uetr() + "/status", Map.class);
        assertThat(status.get("state")).isEqualTo("ACCEPTED");
        assertThat(status.get("isoStatus")).isEqualTo("ACSP");
        List<Map<String, Object>> history = (List<Map<String, Object>>) status.get("history");
        assertThat(history).extracting(e -> e.get("to"))
                .containsExactly("RECEIVED", "VALIDATED", "FX_QUOTED", "ACCEPTED");
        Map<String, Object> credited = (Map<String, Object>) status.get("credited");
        assertThat(credited.get("currency")).isEqualTo("INR");
        assertThat(new BigDecimal((String) credited.get("value"))).isPositive();
    }

    @Test
    void unknownUetrIs404() {
        assertThat(http.getForEntity("/payments/" + java.util.UUID.randomUUID() + "/status", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void byteIdenticalRetransmissionReplaysStoredReport() {
        String xml = message(US1, tx(US1, GB1, "10.00"), tx(US1, DE1, "20.00"));
        String first = submit(xml);
        String second = submit(xml);
        assertThat(second).isEqualTo(first);
        assertThat(paymentCount()).isEqualTo(2);
        assertThat(position(US1.bic())).isEqualTo(-3_000);
    }

    @Test
    void reusedMsgIdWithDifferentContentIsRejected() {
        submit(message("MSG-FIXED-1", US1, tx(US1, GB1, "10.00")));
        String report = submit(message("MSG-FIXED-1", US1, tx(US1, GB1, "11.00")));
        assertThat(groupStatus(report)).isEqualTo("RJCT");
        assertThat(reasonCodes(report)).containsExactly("AM05");
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void sameUetrInNewMessageIsIdempotentButDifferentContentIsRejected() {
        Pacs008Writer.Tx original = tx(US1, GB1, "10.00");
        submit(message(US1, original));
        // Same transaction resent in a new message (e.g. after a timeout): replayed, not re-processed.
        String replay = submit(message(US1, original));
        assertThat(txStatuses(replay)).containsExactly("ACSP");
        assertThat(position(US1.bic())).isEqualTo(-1_000);
        // Same UETR, different amount: rejected, original untouched.
        Pacs008Writer.Tx tampered = new Pacs008Writer.Tx(original.uetr(), original.endToEndId(), original.txId(),
                original.debtorAgent(), original.creditorAgent(), original.debtorName(), original.debtorAccount(),
                original.creditorName(), original.creditorAccount(), original.currency(), new BigDecimal("99.00"));
        String report = submit(message(US1, tampered));
        assertThat(txStatuses(report)).containsExactly("RJCT");
        assertThat(reasonCodes(report)).containsExactly("AM05");
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(position(US1.bic())).isEqualTo(-1_000);
    }

    @Test
    void duplicateEndToEndIdFromSameDebtorAgentIsRejected() {
        Pacs008Writer.Tx a = tx(US1, GB1, "10.00");
        Pacs008Writer.Tx b = tx(US1, GB1, "12.00");
        Pacs008Writer.Tx dup = new Pacs008Writer.Tx(b.uetr(), a.endToEndId(), b.txId(), b.debtorAgent(),
                b.creditorAgent(), b.debtorName(), b.debtorAccount(), b.creditorName(), b.creditorAccount(),
                b.currency(), b.amount());
        submit(message(US1, a));
        String report = submit(message(US1, dup));
        assertThat(txStatuses(report)).containsExactly("RJCT");
        assertThat(reasonCodes(report)).containsExactly("AM05");
    }

    @Test
    void schemaInvalidMessageIsRejectedFf01() {
        String xml = message(US1, tx(US1, GB1, "10.00")).replace("<BICFI>XB06GB2L</BICFI>", "<BICFI>not-a-bic</BICFI>");
        String report = submit(xml);
        assertThat(groupStatus(report)).isEqualTo("RJCT");
        assertThat(reasonCodes(report)).containsExactly("FF01");
        assertThat(paymentCount()).isZero();
    }

    @Test
    void externalEntitiesAreNotResolved() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE d [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"><FIToFICstmrCdtTrf>
                <GrpHdr><MsgId>&xxe;</MsgId></GrpHdr></FIToFICstmrCdtTrf></Document>""";
        String report = submit(xml);
        assertThat(reasonCodes(report)).containsExactly("FF01");
        assertThat(report).doesNotContain("root:");
    }

    @Test
    void groupLevelChecks() {
        String wrongCount = message(US1, tx(US1, GB1, "10.00")).replace("<NbOfTxs>1</NbOfTxs>", "<NbOfTxs>2</NbOfTxs>");
        assertThat(reasonCodes(submit(wrongCount))).containsExactly("AM18");
        String wrongSum = message(US1, tx(US1, GB1, "10.00")).replace("<CtrlSum>10.00</CtrlSum>", "<CtrlSum>10.01</CtrlSum>");
        assertThat(reasonCodes(submit(wrongSum))).containsExactly("AM10");
        assertThat(paymentCount()).isZero();
    }

    @Test
    void businessRuleRejections() {
        record Case(Pacs008Writer.Tx tx, String instructing, String expected) {}
        Pacs008Writer.Tx base = tx(US1, GB1, "10.00");
        List<Case> cases = List.of(
                // Debtor agent must instruct in its own currency.
                new Case(withCurrency(tx(US1, GB1, "10.00"), "EUR"), US1.bic(), "AM03"),
                // JPY has no minor unit.
                new Case(tx(JP1, US1, "1000.5"), JP1.bic(), "AM12"),
                // Creditor IBAN with a broken check digit.
                new Case(withCreditorAccount(base, Pacs008Writer.Account.iban("GB00" + base.creditorAccount().iban().substring(4))), US1.bic(), "AC03"),
                // Mexican CLABE with a wrong check digit.
                new Case(withCreditorAccount(tx(US1, MX1, "10.00"), Pacs008Writer.Account.other("923000000000000001")), US1.bic(), "AC03"),
                // Unknown creditor agent.
                new Case(withCreditorAgent(tx(US1, GB1, "10.00"), "XB99GB2L"), US1.bic(), "RC04"),
                // On-us.
                new Case(withCreditorAgent(tx(US1, GB1, "10.00"), US1.bic()), US1.bic(), "AG03"),
                // Above the per-payment limit (USD 1,000,000.00).
                new Case(tx(US1, GB1, "1000000.01"), US1.bic(), "AM02"));
        for (Case c : cases) {
            String xml = Pacs008Writer.write(new Pacs008Writer.Message("MSG-" + java.util.UUID.randomUUID().toString().substring(0, 20),
                    java.time.Instant.now(), c.instructing(), null, java.time.LocalDate.now(java.time.ZoneOffset.UTC), true,
                    List.of(c.tx())));
            String report = submit(xml);
            assertThat(txStatuses(report)).as(c.expected()).containsExactly("RJCT");
            assertThat(reasonCodes(report)).as(c.expected()).containsExactly(c.expected());
        }
        // Debtor agent different from the instructing agent.
        String report = submit(message(GB1, tx(US1, DE1, "10.00")));
        assertThat(reasonCodes(report)).containsExactly("RC03");
        // Settlement date in the past.
        String old = message(US1, tx(US1, GB1, "10.00"))
                .replaceAll("<IntrBkSttlmDt>[0-9-]+</IntrBkSttlmDt>", "<IntrBkSttlmDt>2020-01-02</IntrBkSttlmDt>");
        assertThat(reasonCodes(submit(old))).containsExactly("DT01");
        assertThat(position(US1.bic())).isZero();
    }

    @Test
    void mixedOutcomesGivePartialGroupStatus() {
        String report = submit(message(US1, tx(US1, GB1, "10.00"), withCurrency(tx(US1, GB1, "10.00"), "EUR")));
        assertThat(groupStatus(report)).isEqualTo("PART");
        assertThat(txStatuses(report)).containsExactly("ACSP", "RJCT");
    }

    @Test
    void concurrentRetriesOfOnePaymentAreProcessedExactlyOnce() throws Exception {
        Pacs008Writer.Tx tx = tx(US1, GB1, "250.00");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                calls.add(() -> submit(message(US1, tx))); // distinct MsgIds, same UETR
            }
            for (Future<String> f : pool.invokeAll(calls)) {
                assertThat(txStatuses(f.get())).containsExactly("ACSP");
            }
        } finally {
            pool.shutdown();
        }
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(position(US1.bic())).isEqualTo(-25_000);
        assertThat(jdbc.sql("select count(*) from payment_event").query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void concurrentTrafficKeepsPositionsBalanced() throws Exception {
        var banks = List.of(US1, US2, GB1, DE1, IN1, MX1, JP1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                var from = banks.get(i % banks.size());
                var to = banks.get((i * 3 + 1) % banks.size());
                if (from == to) {
                    continue;
                }
                String amount = from.currency().equals("JPY") ? "15000" : "100.00";
                calls.add(() -> submit(message(from, tx(from, to, amount))));
            }
            for (Future<String> f : pool.invokeAll(calls)) {
                assertThat(txStatuses(f.get())).containsExactly("ACSP");
            }
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.sql("select sum(position) from participant_position").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from payment where state = 'ACCEPTED'").query(Long.class).single())
                .isEqualTo(paymentCount());
    }

    static Pacs008Writer.Tx withCurrency(Pacs008Writer.Tx t, String ccy) {
        return new Pacs008Writer.Tx(t.uetr(), t.endToEndId(), t.txId(), t.debtorAgent(), t.creditorAgent(),
                t.debtorName(), t.debtorAccount(), t.creditorName(), t.creditorAccount(), ccy, t.amount());
    }

    static Pacs008Writer.Tx withCreditorAccount(Pacs008Writer.Tx t, Pacs008Writer.Account a) {
        return new Pacs008Writer.Tx(t.uetr(), t.endToEndId(), t.txId(), t.debtorAgent(), t.creditorAgent(),
                t.debtorName(), t.debtorAccount(), t.creditorName(), a, t.currency(), t.amount());
    }

    static Pacs008Writer.Tx withCreditorAgent(Pacs008Writer.Tx t, String bic) {
        return new Pacs008Writer.Tx(t.uetr(), t.endToEndId(), t.txId(), t.debtorAgent(), bic,
                t.debtorName(), t.debtorAccount(), t.creditorName(), t.creditorAccount(), t.currency(), t.amount());
    }
}
