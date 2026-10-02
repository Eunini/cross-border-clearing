package io.github.eunini.clearing.iso.sample;

import io.github.eunini.clearing.iso.pacs008.AccountIdentification4Choice;
import io.github.eunini.clearing.iso.pacs008.ActiveCurrencyAndAmount;
import io.github.eunini.clearing.iso.pacs008.BranchAndFinancialInstitutionIdentification6;
import io.github.eunini.clearing.iso.pacs008.CashAccount38;
import io.github.eunini.clearing.iso.pacs008.ChargeBearerType1Code;
import io.github.eunini.clearing.iso.pacs008.CreditTransferTransaction39;
import io.github.eunini.clearing.iso.pacs008.Document;
import io.github.eunini.clearing.iso.pacs008.FIToFICustomerCreditTransferV08;
import io.github.eunini.clearing.iso.pacs008.FinancialInstitutionIdentification18;
import io.github.eunini.clearing.iso.pacs008.GenericAccountIdentification1;
import io.github.eunini.clearing.iso.pacs008.GroupHeader93;
import io.github.eunini.clearing.iso.pacs008.PartyIdentification135;
import io.github.eunini.clearing.iso.pacs008.PaymentIdentification7;
import io.github.eunini.clearing.iso.pacs008.SettlementInstruction7;
import io.github.eunini.clearing.iso.pacs008.SettlementMethod1Code;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.GregorianCalendar;
import java.util.List;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.namespace.QName;

/**
 * Builds pacs.008.001.08 messages for simulated participants (load generator
 * and tests). Output is not schema-validated here so tests can also produce
 * deliberately broken messages; the gateway validates everything it receives.
 */
public final class Pacs008Writer {

    public static final String NS = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";

    /** Account: exactly one of iban / other is set. */
    public record Account(String iban, String other) {
        public static Account iban(String iban) {
            return new Account(iban, null);
        }

        public static Account other(String id) {
            return new Account(null, id);
        }
    }

    public record Tx(String uetr, String endToEndId, String txId, String debtorAgent, String creditorAgent,
                     String debtorName, Account debtorAccount, String creditorName, Account creditorAccount,
                     String currency, BigDecimal amount) {}

    public record Message(String msgId, Instant created, String instructingAgent, String instructedAgent,
                          LocalDate settlementDate, boolean withControlSum, List<Tx> transactions) {}

    private static final JAXBContext CONTEXT;
    private static final DatatypeFactory DATATYPES;

    static {
        try {
            CONTEXT = JAXBContext.newInstance(Document.class);
            DATATYPES = DatatypeFactory.newInstance();
        } catch (JAXBException | DatatypeConfigurationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Pacs008Writer() {}

    public static String write(Message m) {
        GroupHeader93 hdr = new GroupHeader93();
        hdr.setMsgId(m.msgId());
        hdr.setCreDtTm(DATATYPES.newXMLGregorianCalendar(GregorianCalendar.from(m.created().atZone(ZoneOffset.UTC))));
        hdr.setNbOfTxs(Integer.toString(m.transactions().size()));
        if (m.withControlSum()) {
            hdr.setCtrlSum(m.transactions().stream().map(Tx::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        hdr.setIntrBkSttlmDt(date(m.settlementDate()));
        SettlementInstruction7 sttl = new SettlementInstruction7();
        sttl.setSttlmMtd(SettlementMethod1Code.CLRG);
        hdr.setSttlmInf(sttl);
        hdr.setInstgAgt(agent(m.instructingAgent()));
        if (m.instructedAgent() != null) {
            hdr.setInstdAgt(agent(m.instructedAgent()));
        }
        FIToFICustomerCreditTransferV08 body = new FIToFICustomerCreditTransferV08();
        body.setGrpHdr(hdr);
        for (Tx t : m.transactions()) {
            body.getCdtTrfTxInf().add(tx(t));
        }
        Document doc = new Document();
        doc.setFIToFICstmrCdtTrf(body);
        try {
            Marshaller marshaller = CONTEXT.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter out = new StringWriter(2048);
            marshaller.marshal(new JAXBElement<>(new QName(NS, "Document"), Document.class, doc), out);
            return out.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CreditTransferTransaction39 tx(Tx t) {
        CreditTransferTransaction39 tx = new CreditTransferTransaction39();
        PaymentIdentification7 id = new PaymentIdentification7();
        id.setEndToEndId(t.endToEndId());
        id.setTxId(t.txId());
        id.setUETR(t.uetr());
        tx.setPmtId(id);
        ActiveCurrencyAndAmount amt = new ActiveCurrencyAndAmount();
        amt.setCcy(t.currency());
        amt.setValue(t.amount());
        tx.setIntrBkSttlmAmt(amt);
        tx.setChrgBr(ChargeBearerType1Code.SHAR);
        tx.setDbtr(party(t.debtorName()));
        tx.setDbtrAcct(account(t.debtorAccount()));
        tx.setDbtrAgt(agent(t.debtorAgent()));
        tx.setCdtrAgt(agent(t.creditorAgent()));
        tx.setCdtr(party(t.creditorName()));
        tx.setCdtrAcct(account(t.creditorAccount()));
        return tx;
    }

    private static BranchAndFinancialInstitutionIdentification6 agent(String bic) {
        FinancialInstitutionIdentification18 fi = new FinancialInstitutionIdentification18();
        fi.setBICFI(bic);
        BranchAndFinancialInstitutionIdentification6 a = new BranchAndFinancialInstitutionIdentification6();
        a.setFinInstnId(fi);
        return a;
    }

    private static PartyIdentification135 party(String name) {
        PartyIdentification135 p = new PartyIdentification135();
        p.setNm(name);
        return p;
    }

    private static CashAccount38 account(Account a) {
        if (a == null) {
            return null;
        }
        AccountIdentification4Choice id = new AccountIdentification4Choice();
        if (a.iban() != null) {
            id.setIBAN(a.iban());
        } else {
            GenericAccountIdentification1 other = new GenericAccountIdentification1();
            other.setId(a.other());
            id.setOthr(other);
        }
        CashAccount38 acct = new CashAccount38();
        acct.setId(id);
        return acct;
    }

    private static javax.xml.datatype.XMLGregorianCalendar date(LocalDate d) {
        return DATATYPES.newXMLGregorianCalendarDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth(),
                DatatypeConstants.FIELD_UNDEFINED);
    }
}
