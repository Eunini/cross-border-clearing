package io.github.eunini.clearing.gateway.iso;

import io.github.eunini.clearing.iso.pacs008.AccountIdentification4Choice;
import io.github.eunini.clearing.iso.pacs008.BranchAndFinancialInstitutionIdentification6;
import io.github.eunini.clearing.iso.pacs008.CashAccount38;
import io.github.eunini.clearing.iso.pacs008.CreditTransferTransaction39;
import io.github.eunini.clearing.iso.pacs008.Document;
import io.github.eunini.clearing.iso.pacs008.FIToFICustomerCreditTransferV08;
import io.github.eunini.clearing.iso.pacs008.GroupHeader93;
import io.github.eunini.clearing.iso.pacs008.PartyIdentification135;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.xml.datatype.XMLGregorianCalendar;

/** Maps the JAXB tree of a schema-valid pacs.008 to {@link CreditTransfer}s. */
public final class Pacs008Mapper {

    private Pacs008Mapper() {}

    /** Group-level data needed for the status report and message-level checks. */
    public record Group(String msgId, String instructingAgent, String nbOfTxs, BigDecimal ctrlSum,
                        XMLGregorianCalendar creationDateTime, List<CreditTransfer> transactions) {}

    public static Group map(Document doc) {
        FIToFICustomerCreditTransferV08 msg = doc.getFIToFICstmrCdtTrf();
        GroupHeader93 hdr = msg.getGrpHdr();
        String instg = bic(hdr.getInstgAgt());
        String instd = bic(hdr.getInstdAgt());
        String method = hdr.getSttlmInf() != null && hdr.getSttlmInf().getSttlmMtd() != null
                ? hdr.getSttlmInf().getSttlmMtd().value() : null;
        LocalDate groupDate = toDate(hdr.getIntrBkSttlmDt());
        List<CreditTransfer> txs = msg.getCdtTrfTxInf().stream()
                .map(tx -> map(hdr.getMsgId(), instg, instd, method, groupDate, tx))
                .toList();
        return new Group(hdr.getMsgId(), instg, hdr.getNbOfTxs(), hdr.getCtrlSum(), hdr.getCreDtTm(), txs);
    }

    private static CreditTransfer map(String msgId, String instg, String instd, String method,
                                      LocalDate groupDate, CreditTransferTransaction39 tx) {
        var pmtId = tx.getPmtId();
        LocalDate date = tx.getIntrBkSttlmDt() != null ? toDate(tx.getIntrBkSttlmDt()) : groupDate;
        return new CreditTransfer(
                msgId, instg, instd, method,
                pmtId.getUETR(), pmtId.getEndToEndId(), pmtId.getTxId(), pmtId.getInstrId(),
                bic(tx.getDbtrAgt()), bic(tx.getCdtrAgt()),
                name(tx.getDbtr()), account(tx.getDbtrAcct()),
                name(tx.getCdtr()), account(tx.getCdtrAcct()),
                tx.getIntrBkSttlmAmt().getCcy(), tx.getIntrBkSttlmAmt().getValue(), date);
    }

    private static String bic(BranchAndFinancialInstitutionIdentification6 agent) {
        if (agent == null || agent.getFinInstnId() == null) {
            return null;
        }
        return agent.getFinInstnId().getBICFI();
    }

    private static String name(PartyIdentification135 party) {
        return party == null ? null : party.getNm();
    }

    private static AccountRef account(CashAccount38 acct) {
        if (acct == null || acct.getId() == null) {
            return AccountRef.none();
        }
        AccountIdentification4Choice id = acct.getId();
        return new AccountRef(id.getIBAN(), id.getOthr() == null ? null : id.getOthr().getId());
    }

    private static LocalDate toDate(XMLGregorianCalendar cal) {
        if (cal == null) {
            return null;
        }
        return LocalDate.of(cal.getYear(), cal.getMonth(), cal.getDay());
    }
}
