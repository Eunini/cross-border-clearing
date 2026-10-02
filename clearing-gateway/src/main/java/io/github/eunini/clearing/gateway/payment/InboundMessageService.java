package io.github.eunini.clearing.gateway.payment;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.iso.CreditTransfer;
import io.github.eunini.clearing.gateway.iso.Iso20022Codec;
import io.github.eunini.clearing.gateway.iso.Pacs002Builder;
import io.github.eunini.clearing.gateway.iso.Pacs008Mapper;
import io.github.eunini.clearing.gateway.iso.ReasonCode;
import io.github.eunini.clearing.gateway.iso.Rejection;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Entry point for inbound pacs.008 messages.
 *
 * <p>Message-level idempotency: (InstgAgt, MsgId) is unique. A byte-identical
 * retransmission returns the stored pacs.002; a different message reusing the
 * MsgId is rejected AM05. If a message was interrupted mid-way (stored without
 * a response), a retransmission is re-processed and the per-transaction UETR
 * idempotency makes already-processed transactions replay their outcome.
 *
 * <p>Transactions inside a message are submitted to the payment sequencer in
 * order, which keeps a debtor agent's payments in FIFO order.
 */
@Service
public class InboundMessageService {

    private static final Pattern MSG_ID = Pattern.compile("<(?:\\w+:)?MsgId>([^<]{1,35})</(?:\\w+:)?MsgId>");

    private final Iso20022Codec codec;
    private final PaymentProcessor processor;
    private final JdbcClient jdbc;
    private final ClearingProperties props;
    private final Clock clock;

    public InboundMessageService(Iso20022Codec codec, PaymentProcessor processor, JdbcClient jdbc,
                                 ClearingProperties props, Clock clock) {
        this.codec = codec;
        this.processor = processor;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public String handlePacs008(byte[] body) {
        var parsed = codec.parsePacs008(body);
        if (!parsed.valid()) {
            return codec.writePacs002(Pacs002Builder.groupRejection(sniffMsgId(body), null, null,
                    Rejection.of(ReasonCode.FF01, "Message is not a schema-valid pacs.008.001.08"),
                    parsed.errors(), clock.instant()));
        }
        Pacs008Mapper.Group group = Pacs008Mapper.map(parsed.document());
        Optional<Rejection> groupProblem = checkGroup(group);
        if (groupProblem.isPresent()) {
            return codec.writePacs002(Pacs002Builder.groupRejection(group.msgId(), group.creationDateTime(),
                    group.nbOfTxs(), groupProblem.get(), List.of(), clock.instant()));
        }

        String sha = CreditTransfer.sha256Hex(body);
        String instg = group.instructingAgent().length() == 11
                ? group.instructingAgent().substring(0, 8) : group.instructingAgent();
        Optional<Long> inserted = jdbc.sql("""
                        insert into inbound_message (instg_agent, msg_id, payload_sha256, nb_of_txs)
                        values (:a, :m, :s, :n) on conflict (instg_agent, msg_id) do nothing returning id""")
                .param("a", instg).param("m", group.msgId()).param("s", sha)
                .param("n", group.transactions().size())
                .query(Long.class).optional();
        long messageId;
        if (inserted.isPresent()) {
            messageId = inserted.get();
        } else {
            var existing = jdbc.sql("""
                            select id, payload_sha256, response_xml from inbound_message
                            where instg_agent = :a and msg_id = :m""")
                    .param("a", instg).param("m", group.msgId())
                    .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)})
                    .single();
            if (!Objects.equals(existing[1], sha)) {
                return codec.writePacs002(Pacs002Builder.groupRejection(group.msgId(), group.creationDateTime(),
                        group.nbOfTxs(), Rejection.of(ReasonCode.AM05,
                                "MsgId already used by " + instg + " for a different message"),
                        List.of(), clock.instant()));
            }
            if (existing[2] != null) {
                return (String) existing[2]; // byte-identical retransmission: replay the stored report
            }
            messageId = (Long) existing[0];
        }

        // Submitted in order, so the sequencer sees a message's transactions in FIFO order.
        List<CompletableFuture<TxOutcome>> futures = new ArrayList<>(group.transactions().size());
        for (CreditTransfer t : group.transactions()) {
            futures.add(processor.submit(t, messageId));
        }
        List<TxOutcome> outcomes = futures.stream().map(CompletableFuture::join).toList();
        String response = codec.writePacs002(Pacs002Builder.transactions(group.msgId(), group.creationDateTime(),
                outcomes, clock.instant()));
        jdbc.sql("update inbound_message set response_xml = :r where id = :id")
                .param("r", response).param("id", messageId).update();
        return response;
    }

    private Optional<Rejection> checkGroup(Pacs008Mapper.Group g) {
        int n = g.transactions().size();
        if (g.instructingAgent() == null) {
            return Optional.of(Rejection.of(ReasonCode.RC03, "GrpHdr/InstgAgt/FinInstnId/BICFI is mandatory"));
        }
        if (!Integer.toString(n).equals(g.nbOfTxs().replaceFirst("^0+(?=\\d)", ""))) {
            return Optional.of(Rejection.of(ReasonCode.AM18, "NbOfTxs " + g.nbOfTxs() + " but " + n + " CdtTrfTxInf"));
        }
        if (n > props.maxTransactionsPerMessage()) {
            return Optional.of(Rejection.of(ReasonCode.AM18,
                    "At most " + props.maxTransactionsPerMessage() + " transactions per message"));
        }
        if (g.ctrlSum() != null) {
            BigDecimal sum = g.transactions().stream().map(CreditTransfer::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(g.ctrlSum()) != 0) {
                return Optional.of(Rejection.of(ReasonCode.AM10, "CtrlSum " + g.ctrlSum() + " but amounts sum to " + sum));
            }
        }
        return Optional.empty();
    }

    private static String sniffMsgId(byte[] body) {
        int len = Math.min(body.length, 4096);
        Matcher m = MSG_ID.matcher(new String(body, 0, len, java.nio.charset.StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : null;
    }
}
