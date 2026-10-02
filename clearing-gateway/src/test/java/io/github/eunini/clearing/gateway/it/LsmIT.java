package io.github.eunini.clearing.gateway.it;

import static io.github.eunini.clearing.gateway.support.Messages.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.clearing.gateway.lsm.LsmService;
import io.github.eunini.clearing.gateway.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class LsmIT extends IntegrationTestBase {

    @Autowired
    LsmService lsm;

    private String submit(String xml) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        return http.postForObject("/iso20022/pacs.008", new HttpEntity<>(xml, h), String.class);
    }

    private String state(String uetr) {
        return jdbc.sql("select state from payment where uetr = cast(:u as uuid)").param("u", uetr)
                .query(String.class).single();
    }

    private void cap(Bank bank, long minor) {
        participants.updateCap(bank.bic(), minor);
    }

    @Test
    void gridlockedPaymentsAreQueuedThenReleasedTogether() {
        cap(US1, 10_000); // USD 100.00
        cap(GB1, 10_000);
        var a = tx(US1, GB1, "1000.00");
        var b = tx(GB1, US1, "780.00"); // ~ USD 999.7 at the simulated GBP rate
        assertThat(txStatuses(submit(message(US1, a)))).containsExactly("PDNG");
        assertThat(txStatuses(submit(message(GB1, b)))).containsExactly("PDNG");
        assertThat(position(US1.bic())).isZero();

        LsmService.RunResult run = lsm.runOnce();

        assertThat(run.released()).isEqualTo(2);
        assertThat(state(a.uetr())).isEqualTo("ACCEPTED");
        assertThat(state(b.uetr())).isEqualTo("ACCEPTED");
        assertThat(position(US1.bic()) + position(GB1.bic())).isZero();
        assertThat(position(US1.bic())).isBetween(-10_000L, 0L);
        assertThat(jdbc.sql("select sum(queued_count) from participant_position").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from lsm_run").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void newPaymentsQueueBehindEarlierOnesFifo() {
        cap(US1, 10_000);
        var big = tx(US1, GB1, "1000.00");
        var small = tx(US1, GB1, "1.00"); // would fit the cap on its own
        assertThat(txStatuses(submit(message(US1, big)))).containsExactly("PDNG");
        assertThat(txStatuses(submit(message(US1, small)))).containsExactly("PDNG");
        lsm.runOnce();
        // The head of the queue does not fit, so FIFO keeps both queued.
        assertThat(state(big.uetr())).isEqualTo("QUEUED");
        assertThat(state(small.uetr())).isEqualTo("QUEUED");
        // An incoming payment gives US1 liquidity; the next run releases both in order.
        cap(GB1, 2_000_000_000);
        assertThat(txStatuses(submit(message(GB1, tx(GB1, US1, "800.00"))))).containsExactly("ACSP");
        lsm.runOnce();
        assertThat(state(big.uetr())).isEqualTo("ACCEPTED");
        assertThat(state(small.uetr())).isEqualTo("ACCEPTED");
    }

    @Test
    void queuedPaymentsWhoseQuoteExpiresAreRejectedAb01() {
        cap(US1, 0);
        var t = tx(US1, GB1, "50.00");
        submit(message(US1, t));
        jdbc.sql("update payment set quote_expires_at = now() - interval '1 second' where uetr = cast(:u as uuid)")
                .param("u", t.uetr()).update();
        LsmService.RunResult run = lsm.runOnce();
        assertThat(run.expired()).isEqualTo(1);
        assertThat(state(t.uetr())).isEqualTo("REJECTED");
        assertThat(jdbc.sql("select reason_code from payment where uetr = cast(:u as uuid)").param("u", t.uetr())
                .query(String.class).single()).isEqualTo("AB01");
        assertThat(jdbc.sql("select sum(queued_count) from participant_position").query(Long.class).single()).isZero();
        // The EndToEndId is free again, so the bank can resend with a new UETR.
        assertThat(jdbc.sql("select count(*) from end_to_end_ref").query(Long.class).single()).isZero();
    }
}
