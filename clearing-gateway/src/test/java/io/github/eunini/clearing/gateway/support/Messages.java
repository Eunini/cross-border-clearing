package io.github.eunini.clearing.gateway.support;

import io.github.eunini.clearing.iso.sample.Pacs008Writer;
import io.github.eunini.clearing.iso.sample.SampleAccounts;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.NodeList;

/** Test helpers to build pacs.008 messages and read pacs.002 responses. */
public final class Messages {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Random RNG = new Random(7);

    public record Bank(String bic, String country, String currency) {}

    public static final Bank US1 = new Bank("XB01USNY", "US", "USD");
    public static final Bank US2 = new Bank("XB02USNY", "US", "USD");
    public static final Bank GB1 = new Bank("XB06GB2L", "GB", "GBP");
    public static final Bank DE1 = new Bank("XB10DEFF", "DE", "EUR");
    public static final Bank IN1 = new Bank("XB19INBB", "IN", "INR");
    public static final Bank MX1 = new Bank("XB23MXMM", "MX", "MXN");
    public static final Bank JP1 = new Bank("XB35JPJT", "JP", "JPY");

    private Messages() {}

    public static Pacs008Writer.Tx tx(Bank from, Bank to, String amount) {
        long n = SEQ.incrementAndGet();
        return new Pacs008Writer.Tx(UUID.randomUUID().toString(), "E2E-" + n, "TX-" + n, from.bic(), to.bic(),
                "Debtor " + n, SampleAccounts.forParticipant(from.country(), from.bic(), RNG), "Creditor " + n,
                SampleAccounts.forParticipant(to.country(), to.bic(), RNG), from.currency(), new BigDecimal(amount));
    }

    public static String message(Bank instructing, Pacs008Writer.Tx... txs) {
        return Pacs008Writer.write(new Pacs008Writer.Message("MSG-" + SEQ.incrementAndGet(), Instant.now(),
                instructing.bic(), null, LocalDate.now(ZoneOffset.UTC), true, List.of(txs)));
    }

    public static String message(String msgId, Bank instructing, Pacs008Writer.Tx... txs) {
        return Pacs008Writer.write(new Pacs008Writer.Message(msgId, Instant.now(), instructing.bic(), null,
                LocalDate.now(ZoneOffset.UTC), true, List.of(txs)));
    }

    /** Evaluates a namespace-agnostic XPath (use local-name()) against a response. */
    public static List<String> xpath(String xml, String expr) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(expr, doc, XPathConstants.NODESET);
            return java.util.stream.IntStream.range(0, nodes.getLength())
                    .mapToObj(i -> nodes.item(i).getTextContent()).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<String> txStatuses(String pacs002) {
        return xpath(pacs002, "//*[local-name()='TxInfAndSts']/*[local-name()='TxSts']");
    }

    public static List<String> reasonCodes(String pacs002) {
        return xpath(pacs002, "//*[local-name()='StsRsnInf']/*[local-name()='Rsn']/*[local-name()='Cd']");
    }

    public static String groupStatus(String pacs002) {
        return xpath(pacs002, "//*[local-name()='OrgnlGrpInfAndSts']/*[local-name()='GrpSts']").getFirst();
    }
}
