package io.github.eunini.clearing.gateway.web;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.cycle.CycleRepository;
import io.github.eunini.clearing.gateway.cycle.CycleSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Aggregates for the operations dashboard (static page at /). Amounts are settlement-currency minor units. */
@RestController
public class DashboardController {

    private final JdbcClient jdbc;
    private final CycleRepository cycles;
    private final ClearingProperties props;

    public DashboardController(JdbcClient jdbc, CycleRepository cycles, ClearingProperties props) {
        this.jdbc = jdbc;
        this.cycles = cycles;
        this.props = props;
    }

    public record CurrencyRow(String currency, long gross, long net, int participants) {}

    public record ParticipantRow(String bic, String country, String currency, long position, long cap,
                                 int queued) {}

    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlementCurrency", props.settlementCurrency());
        out.put("openCycle", cycles.open().orElse(null));

        Map<String, Object> live = jdbc.sql("""
                        select
                          (select count(*) from payment p join clearing_cycle c on c.id = p.cycle_id
                             where c.status = 'OPEN') as open_cycle_payments,
                          (select coalesce(sum(settlement_amount), 0) from payment p join clearing_cycle c
                             on c.id = p.cycle_id where c.status = 'OPEN') as open_cycle_value,
                          (select count(*) from payment where state = 'QUEUED') as queued,
                          (select count(*) from payment where accepted_at > now() - interval '60 seconds')
                             as accepted_last_60s,
                          (select count(*) from payment where state = 'REJECTED') as rejected_total""")
                .query().singleRow();
        out.put("live", live);

        Map<String, Object> totals = jdbc.sql("""
                        select count(*) as cycles, coalesce(sum(payment_count), 0) as payments,
                               coalesce(sum(gross_value), 0) as gross, coalesce(sum(net_value), 0) as net,
                               coalesce(sum(transfer_count), 0) as transfers
                        from clearing_cycle where status in ('NETTED', 'SETTLED') and payment_count > 0""")
                .query().singleRow();
        out.put("totals", totals);

        Map<String, Object> lsm = jdbc.sql("""
                        select count(*) as runs, coalesce(sum(released), 0) as released,
                               coalesce(sum(released_by_offsetting), 0) as released_by_offsetting,
                               coalesce(sum(released_value), 0) as released_value
                        from lsm_run""").query().singleRow();
        out.put("lsm", lsm);

        List<CycleSummary> recent = cycles.recent(20);
        out.put("cycles", recent);

        Long latest = recent.stream().filter(c -> c.paymentCount() != null && c.paymentCount() > 0)
                .map(CycleSummary::id).findFirst().orElse(null);
        out.put("currencyCycleId", latest);
        out.put("currencies", latest == null ? List.of() : jdbc.sql("""
                        select currency, sum(gross_debit) as gross,
                               sum(case when net > 0 then net else 0 end) as net, count(*) as participants
                        from cycle_currency_position where cycle_id = :id group by currency order by gross desc""")
                .param("id", latest)
                .query((rs, n) -> new CurrencyRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)))
                .list());

        out.put("participants", jdbc.sql("""
                        select p.bic, p.country, p.currency, pp.position, p.net_debit_cap, pp.queued_count
                        from participant p join participant_position pp on pp.participant_id = p.id order by p.id""")
                .query((rs, n) -> new ParticipantRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getInt(6)))
                .list());
        return out;
    }
}
