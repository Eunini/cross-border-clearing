package io.github.eunini.clearing.gateway.it;

import static io.github.eunini.clearing.gateway.support.Messages.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.clearing.gateway.cycle.CycleService;
import io.github.eunini.clearing.gateway.cycle.CycleSummary;
import io.github.eunini.clearing.gateway.outbox.OutboxPublisher;
import io.github.eunini.clearing.gateway.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class CycleIT extends IntegrationTestBase {

    @Autowired
    CycleService cycles;

    @Autowired
    OutboxPublisher publisher;

    private void submit(String xml) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        String r = http.postForObject("/iso20022/pacs.008", new HttpEntity<>(xml, h), String.class);
        assertThat(txStatuses(r)).allMatch(s -> s.equals("ACSP"));
    }

    private void traffic() {
        submit(message(US1, tx(US1, GB1, "1000.00"), tx(US1, IN1, "500.00")));
        submit(message(GB1, tx(GB1, US1, "700.00"), tx(GB1, DE1, "100.00")));
        submit(message(DE1, tx(DE1, US1, "300.00")));
        submit(message(IN1, tx(IN1, GB1, "20000.00")));
    }

    @Test
    void cutOffNetsAndSettlesTheCycle() {
        traffic();
        CycleSummary closed = http.postForObject("/api/admin/cycles/close", null, CycleSummary.class);

        assertThat(closed.status()).isEqualTo("SETTLED");
        assertThat(closed.paymentCount()).isEqualTo(6);
        assertThat(closed.netValue()).isLessThan(closed.grossValue());
        assertThat(jdbc.sql("select distinct state from payment").query(String.class).list()).containsExactly("SETTLED");
        // Settlement released every reservation: unsettled positions are flat again.
        assertThat(jdbc.sql("select count(*) from participant_position where position <> 0").query(Long.class).single()).isZero();
        // A new cycle is open.
        assertThat(jdbc.sql("select count(*) from clearing_cycle where status = 'OPEN' and id <> :id")
                .param("id", closed.id()).query(Long.class).single()).isEqualTo(1);
        // Transfers recorded and confirmed, cycle positions sum to zero.
        assertThat(jdbc.sql("select sum(net) from cycle_position where cycle_id = :id").param("id", closed.id())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from settlement_instruction where status <> 'CONFIRMED'")
                .query(Long.class).single()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void trackerShowsTheFullLifecycleAfterSettlement() {
        var t = tx(US1, GB1, "42.00");
        submit(message(US1, t));
        cycles.closeNetAndSettle();
        Map<String, Object> status = http.getForObject("/payments/" + t.uetr() + "/status", Map.class);
        assertThat(status.get("isoStatus")).isEqualTo("ACSC");
        assertThat((List<Map<String, Object>>) status.get("history")).extracting(e -> e.get("to"))
                .containsExactly("RECEIVED", "VALIDATED", "FX_QUOTED", "ACCEPTED", "CLEARED", "SETTLED");
    }

    @Test
    void camt053StatementBalancesToZeroOnceSettled() {
        traffic();
        CycleSummary closed = cycles.closeNetAndSettle();
        String camt = http.getForObject("/api/cycles/" + closed.id() + "/statements/" + US1.bic(), String.class);
        // The codec validates outbound messages against the camt.053.001.08 XSD, so reaching here means schema-valid.
        assertThat(xpath(camt, "//*[local-name()='Bal'][*[local-name()='Tp']//*[local-name()='Cd']='CLBD']/*[local-name()='Amt']"))
                .containsExactly("0.00");
        // 4 payment entries for US1 (2 out, 2 in) plus at least one settlement entry.
        assertThat(xpath(camt, "//*[local-name()='Ntry']").size()).isGreaterThanOrEqualTo(5);
        assertThat(xpath(camt, "//*[local-name()='Ntry']/*[local-name()='Sts']/*[local-name()='Cd']")).containsOnly("BOOK");
    }

    @Test
    void engineOutageLeavesCycleClosingAndRecovers() {
        traffic();
        engine.down.set(true);
        CycleSummary closed = cycles.closeNetAndSettle();
        assertThat(closed.status()).isEqualTo("CLOSING");
        assertThat(jdbc.sql("select distinct state from payment").query(String.class).list()).containsExactly("ACCEPTED");
        // New payments keep flowing into the next cycle meanwhile.
        submit(message(US1, tx(US1, GB1, "5.00")));
        engine.down.set(false);
        cycles.processPending();
        assertThat(jdbc.sql("select status from clearing_cycle where id = :id").param("id", closed.id())
                .query(String.class).single()).isEqualTo("SETTLED");
        assertThat(jdbc.sql("select count(*) from payment where state = 'ACCEPTED'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void engineResultFailingReconciliationIsNotSettled() {
        traffic();
        engine.corruptNextResult.set(true);
        CycleSummary closed = cycles.closeNetAndSettle();
        assertThat(closed.status()).isEqualTo("FAILED");
        assertThat(closed.failureReason()).contains("net positions sum to");
        assertThat(jdbc.sql("select count(*) from settlement_instruction").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select distinct state from payment").query(String.class).list()).containsExactly("ACCEPTED");
    }

    @Test
    void outboxDeliversNotificationsExactlyOnce() {
        traffic();
        cycles.closeNetAndSettle();
        int published = 0;
        int n;
        while ((n = publisher.publishBatch(4)) > 0) {
            published += n;
        }
        long events = jdbc.sql("select count(*) from outbox_event").query(Long.class).single();
        assertThat(published).isEqualTo((int) events);
        assertThat(jdbc.sql("select count(*) from participant_notification").query(Long.class).single()).isEqualTo(events);
        assertThat(publisher.publishBatch(100)).isZero();
        // Creditor agents were told to credit their customers.
        assertThat(jdbc.sql("""
                        select count(*) from participant_notification
                        where recipient = :b and event_type = 'PaymentAccepted'""")
                .param("b", GB1.bic()).query(Long.class).single()).isEqualTo(2);
    }
}
