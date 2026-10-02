package io.github.eunini.clearing.gateway.iso;

import io.github.eunini.clearing.gateway.payment.TxOutcome;
import io.github.eunini.clearing.iso.pacs002.Document;
import io.github.eunini.clearing.iso.pacs002.FIToFIPaymentStatusReportV10;
import io.github.eunini.clearing.iso.pacs002.GroupHeader91;
import io.github.eunini.clearing.iso.pacs002.OriginalGroupHeader17;
import io.github.eunini.clearing.iso.pacs002.PaymentTransaction110;
import io.github.eunini.clearing.iso.pacs002.StatusReason6Choice;
import io.github.eunini.clearing.iso.pacs002.StatusReasonInformation12;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.xml.datatype.XMLGregorianCalendar;

/** Builds pacs.002.001.10 FI-to-FI payment status reports. */
public final class Pacs002Builder {

    public static final String ORIGINAL_MESSAGE_NAME = "pacs.008.001.08";

    private Pacs002Builder() {}

    /** Status report for a whole message that was rejected before any transaction was processed. */
    public static Document groupRejection(String originalMsgId, XMLGregorianCalendar originalCreDtTm,
                                          String originalNbOfTxs, Rejection rejection, List<String> details,
                                          Instant now) {
        FIToFIPaymentStatusReportV10 rpt = report(now);
        OriginalGroupHeader17 grp = originalGroup(originalMsgId, originalCreDtTm, originalNbOfTxs);
        grp.setGrpSts("RJCT");
        StatusReasonInformation12 reason = reason(rejection);
        for (String d : details.stream().limit(5).toList()) {
            reason.getAddtlInf().add(trim(d));
        }
        grp.getStsRsnInf().add(reason);
        rpt.getOrgnlGrpInfAndSts().add(grp);
        return wrap(rpt);
    }

    /** Status report with one TxInfAndSts per processed transaction. */
    public static Document transactions(String originalMsgId, XMLGregorianCalendar originalCreDtTm,
                                        List<TxOutcome> outcomes, Instant now) {
        FIToFIPaymentStatusReportV10 rpt = report(now);
        OriginalGroupHeader17 grp = originalGroup(originalMsgId, originalCreDtTm, Integer.toString(outcomes.size()));
        long distinct = outcomes.stream().map(o -> o.state().isoStatus()).distinct().count();
        grp.setGrpSts(distinct == 1 ? outcomes.getFirst().state().isoStatus() : "PART");
        rpt.getOrgnlGrpInfAndSts().add(grp);
        for (TxOutcome o : outcomes) {
            PaymentTransaction110 tx = new PaymentTransaction110();
            tx.setOrgnlInstrId(o.instrId());
            tx.setOrgnlEndToEndId(o.endToEndId());
            tx.setOrgnlTxId(o.txId());
            if (o.uetr() != null && o.uetr().matches(
                    "[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")) {
                tx.setOrgnlUETR(o.uetr());
            }
            tx.setTxSts(o.state().isoStatus());
            if (o.rejection() != null) {
                tx.getStsRsnInf().add(reason(o.rejection()));
            }
            if (o.acceptedAt() != null) {
                tx.setAccptncDtTm(XmlDates.dateTime(o.acceptedAt()));
            }
            rpt.getTxInfAndSts().add(tx);
        }
        return wrap(rpt);
    }

    private static FIToFIPaymentStatusReportV10 report(Instant now) {
        FIToFIPaymentStatusReportV10 rpt = new FIToFIPaymentStatusReportV10();
        GroupHeader91 hdr = new GroupHeader91();
        hdr.setMsgId("STS" + UUID.randomUUID().toString().replace("-", "").substring(0, 29));
        hdr.setCreDtTm(XmlDates.dateTime(now));
        rpt.setGrpHdr(hdr);
        return rpt;
    }

    private static OriginalGroupHeader17 originalGroup(String msgId, XMLGregorianCalendar creDtTm, String nbOfTxs) {
        OriginalGroupHeader17 grp = new OriginalGroupHeader17();
        grp.setOrgnlMsgId(msgId == null || msgId.isBlank() ? "NOTPROVIDED" : trim35(msgId));
        grp.setOrgnlMsgNmId(ORIGINAL_MESSAGE_NAME);
        grp.setOrgnlCreDtTm(creDtTm);
        if (nbOfTxs != null && nbOfTxs.matches("[0-9]{1,15}")) {
            grp.setOrgnlNbOfTxs(nbOfTxs);
        }
        return grp;
    }

    private static StatusReasonInformation12 reason(Rejection r) {
        StatusReasonInformation12 info = new StatusReasonInformation12();
        StatusReason6Choice rsn = new StatusReason6Choice();
        rsn.setCd(r.code().name());
        info.setRsn(rsn);
        if (r.detail() != null && !r.detail().isBlank()) {
            info.getAddtlInf().add(trim(r.detail()));
        }
        return info;
    }

    private static Document wrap(FIToFIPaymentStatusReportV10 rpt) {
        Document doc = new Document();
        doc.setFIToFIPmtStsRpt(rpt);
        return doc;
    }

    private static String trim(String s) {
        return s.length() <= 105 ? s : s.substring(0, 105);
    }

    private static String trim35(String s) {
        return s.length() <= 35 ? s : s.substring(0, 35);
    }
}
