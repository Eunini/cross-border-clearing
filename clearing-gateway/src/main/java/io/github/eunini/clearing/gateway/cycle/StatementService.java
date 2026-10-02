package io.github.eunini.clearing.gateway.cycle;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.iso.Iso20022Codec;
import io.github.eunini.clearing.gateway.iso.XmlDates;
import io.github.eunini.clearing.gateway.participant.Participant;
import io.github.eunini.clearing.gateway.participant.ParticipantDirectory;
import io.github.eunini.clearing.gateway.validation.CurrencyRules;
import io.github.eunini.clearing.iso.camt053.AccountIdentification4Choice;
import io.github.eunini.clearing.iso.camt053.AccountStatement9;
import io.github.eunini.clearing.iso.camt053.ActiveOrHistoricCurrencyAndAmount;
import io.github.eunini.clearing.iso.camt053.AmountAndCurrencyExchange3;
import io.github.eunini.clearing.iso.camt053.AmountAndCurrencyExchangeDetails3;
import io.github.eunini.clearing.iso.camt053.AmountAndDirection35;
import io.github.eunini.clearing.iso.camt053.BalanceType10Choice;
import io.github.eunini.clearing.iso.camt053.BalanceType13;
import io.github.eunini.clearing.iso.camt053.BankToCustomerStatementV08;
import io.github.eunini.clearing.iso.camt053.BankTransactionCodeStructure4;
import io.github.eunini.clearing.iso.camt053.CashAccount39;
import io.github.eunini.clearing.iso.camt053.CashBalance8;
import io.github.eunini.clearing.iso.camt053.CreditDebitCode;
import io.github.eunini.clearing.iso.camt053.DateAndDateTime2Choice;
import io.github.eunini.clearing.iso.camt053.Document;
import io.github.eunini.clearing.iso.camt053.EntryDetails9;
import io.github.eunini.clearing.iso.camt053.EntryStatus1Choice;
import io.github.eunini.clearing.iso.camt053.EntryTransaction10;
import io.github.eunini.clearing.iso.camt053.GenericAccountIdentification1;
import io.github.eunini.clearing.iso.camt053.GroupHeader81;
import io.github.eunini.clearing.iso.camt053.NumberAndSumOfTransactions1;
import io.github.eunini.clearing.iso.camt053.NumberAndSumOfTransactions4;
import io.github.eunini.clearing.iso.camt053.ProprietaryBankTransactionCodeStructure1;
import io.github.eunini.clearing.iso.camt053.ReportEntry10;
import io.github.eunini.clearing.iso.camt053.TotalTransactions6;
import io.github.eunini.clearing.iso.camt053.TransactionReferences6;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Produces a camt.053.001.08 end-of-cycle statement of a participant's
 * clearing position account (in the settlement currency):
 *
 * <ul>
 *   <li>opening balance 0 (each cycle starts flat);</li>
 *   <li>one entry per cleared payment (DBIT when the participant is the
 *       debtor agent, CRDT when creditor agent), with the instructed amount
 *       in the original currency under AmtDtls/InstdAmt;</li>
 *   <li>once settled, one entry per settlement transfer, which brings the
 *       closing balance back to zero.</li>
 * </ul>
 */
@Service
public class StatementService {

    private final JdbcClient jdbc;
    private final Iso20022Codec codec;
    private final ParticipantDirectory participants;
    private final CycleRepository cycles;
    private final ClearingProperties props;
    private final Clock clock;

    public StatementService(JdbcClient jdbc, Iso20022Codec codec, ParticipantDirectory participants,
                            CycleRepository cycles, ClearingProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.participants = participants;
        this.cycles = cycles;
        this.props = props;
        this.clock = clock;
    }

    public String statementXml(long cycleId, String bic) {
        return codec.writeCamt053(statement(cycleId, bic));
    }

    public Document statement(long cycleId, String bic) {
        CycleSummary cycle = cycles.find(cycleId).orElseThrow(() -> new NoSuchElementException("No cycle " + cycleId));
        if (!cycle.status().equals("NETTED") && !cycle.status().equals("SETTLED")) {
            throw new IllegalStateException("Cycle " + cycleId + " is " + cycle.status() + "; statements exist once netted");
        }
        Participant p = participants.byBic(bic).orElseThrow(() -> new NoSuchElementException("No participant " + bic));
        String ccy = props.settlementCurrency();
        boolean settled = cycle.status().equals("SETTLED");
        LocalDate bookingDate = cycle.businessDate();

        AccountStatement9 stmt = new AccountStatement9();
        stmt.setId(("STMT-" + cycleId + "-" + p.bic()));
        stmt.setCreDtTm(XmlDates.dateTime(clock.instant()));
        stmt.setElctrncSeqNb(BigDecimal.valueOf(cycleId));
        CashAccount39 acct = new CashAccount39();
        AccountIdentification4Choice id = new AccountIdentification4Choice();
        GenericAccountIdentification1 other = new GenericAccountIdentification1();
        other.setId("CLR-" + p.bic());
        id.setOthr(other);
        acct.setId(id);
        acct.setCcy(ccy);
        acct.setNm(p.name().length() > 70 ? p.name().substring(0, 70) : p.name());
        stmt.setAcct(acct);

        long[] totals = new long[4]; // credit count, credit sum, debit count, debit sum
        jdbc.sql("""
                        select uetr, end_to_end_id, tx_id, currency, amount, settlement_amount,
                               case when debtor_participant = :p then 'DBIT' else 'CRDT' end as dir
                        from payment where cycle_id = :c and (debtor_participant = :p or creditor_participant = :p)
                          and state in ('CLEARED', 'SETTLED')
                        order by id""")
                .param("c", cycleId).param("p", p.id())
                .query((RowCallbackHandler) rs -> {
                    boolean credit = rs.getString("dir").equals("CRDT");
                    long amt = rs.getLong("settlement_amount");
                    ReportEntry10 e = entry(ccy, amt, credit, settled, bookingDate, "CLRG-PMT");
                    e.setNtryRef(rs.getString("end_to_end_id"));
                    AmountAndCurrencyExchange3 details = new AmountAndCurrencyExchange3();
                    AmountAndCurrencyExchangeDetails3 instructed = new AmountAndCurrencyExchangeDetails3();
                    instructed.setAmt(amount(rs.getString("currency"), rs.getLong("amount")));
                    details.setInstdAmt(instructed);
                    e.setAmtDtls(details);
                    EntryTransaction10 txd = new EntryTransaction10();
                    TransactionReferences6 refs = new TransactionReferences6();
                    refs.setEndToEndId(rs.getString("end_to_end_id"));
                    refs.setTxId(rs.getString("tx_id"));
                    refs.setUETR(rs.getString("uetr"));
                    txd.setRefs(refs);
                    EntryDetails9 ed = new EntryDetails9();
                    ed.getTxDtls().add(txd);
                    e.getNtryDtls().add(ed);
                    stmt.getNtry().add(e);
                    count(totals, credit, amt);
                });

        if (settled) {
            jdbc.sql("""
                            select id, from_participant, to_participant, amount from settlement_instruction
                            where cycle_id = :c and (from_participant = :p or to_participant = :p) order by id""")
                    .param("c", cycleId).param("p", p.id())
                    .query((RowCallbackHandler) rs -> {
                        // Paying into settlement credits the clearing position; receiving debits it.
                        boolean credit = rs.getInt("from_participant") == p.id();
                        long amt = rs.getLong("amount");
                        ReportEntry10 e = entry(ccy, amt, credit, true, bookingDate, "STTL");
                        e.setNtryRef("STTL-" + rs.getLong("id"));
                        e.setAddtlNtryInf("Settlement of cycle " + cycleId + (credit ? " pay-in" : " pay-out"));
                        stmt.getNtry().add(e);
                        count(totals, credit, amt);
                    });
        }

        long closing = totals[1] - totals[3];
        stmt.getBal().add(balance("OPBD", ccy, 0, bookingDate));
        stmt.getBal().add(balance("CLBD", ccy, closing, bookingDate));

        TotalTransactions6 summary = new TotalTransactions6();
        NumberAndSumOfTransactions4 all = new NumberAndSumOfTransactions4();
        all.setNbOfNtries(Long.toString(totals[0] + totals[2]));
        all.setSum(major(ccy, totals[1] + totals[3]));
        AmountAndDirection35 net = new AmountAndDirection35();
        net.setAmt(major(ccy, Math.abs(closing)));
        net.setCdtDbtInd(closing >= 0 ? CreditDebitCode.CRDT : CreditDebitCode.DBIT);
        all.setTtlNetNtry(net);
        summary.setTtlNtries(all);
        summary.setTtlCdtNtries(numberAndSum(ccy, totals[0], totals[1]));
        summary.setTtlDbtNtries(numberAndSum(ccy, totals[2], totals[3]));
        stmt.setTxsSummry(summary);

        BankToCustomerStatementV08 body = new BankToCustomerStatementV08();
        GroupHeader81 hdr = new GroupHeader81();
        hdr.setMsgId("CAMT053-" + cycleId + "-" + p.bic());
        hdr.setCreDtTm(XmlDates.dateTime(clock.instant()));
        hdr.setAddtlInf("Clearing position statement, cycle " + cycleId + (settled ? " (settled)" : " (netted, settlement pending)"));
        body.setGrpHdr(hdr);
        body.getStmt().add(stmt);
        Document doc = new Document();
        doc.setBkToCstmrStmt(body);
        return doc;
    }

    private static void count(long[] totals, boolean credit, long amt) {
        if (credit) {
            totals[0]++;
            totals[1] += amt;
        } else {
            totals[2]++;
            totals[3] += amt;
        }
    }

    private static ReportEntry10 entry(String ccy, long amt, boolean credit, boolean booked, LocalDate date,
                                       String code) {
        ReportEntry10 e = new ReportEntry10();
        e.setAmt(amount(ccy, amt));
        e.setCdtDbtInd(credit ? CreditDebitCode.CRDT : CreditDebitCode.DBIT);
        EntryStatus1Choice sts = new EntryStatus1Choice();
        sts.setCd(booked ? "BOOK" : "PDNG");
        e.setSts(sts);
        DateAndDateTime2Choice d = new DateAndDateTime2Choice();
        d.setDt(XmlDates.date(date));
        e.setBookgDt(d);
        BankTransactionCodeStructure4 btc = new BankTransactionCodeStructure4();
        ProprietaryBankTransactionCodeStructure1 prtry = new ProprietaryBankTransactionCodeStructure1();
        prtry.setCd(code);
        prtry.setIssr("XBCLEARING");
        btc.setPrtry(prtry);
        e.setBkTxCd(btc);
        return e;
    }

    private static CashBalance8 balance(String type, String ccy, long amt, LocalDate date) {
        CashBalance8 b = new CashBalance8();
        BalanceType13 t = new BalanceType13();
        BalanceType10Choice c = new BalanceType10Choice();
        c.setCd(type);
        t.setCdOrPrtry(c);
        b.setTp(t);
        b.setAmt(amount(ccy, Math.abs(amt)));
        b.setCdtDbtInd(amt >= 0 ? CreditDebitCode.CRDT : CreditDebitCode.DBIT);
        DateAndDateTime2Choice d = new DateAndDateTime2Choice();
        d.setDt(XmlDates.date(date));
        b.setDt(d);
        return b;
    }

    private static NumberAndSumOfTransactions1 numberAndSum(String ccy, long count, long sum) {
        NumberAndSumOfTransactions1 n = new NumberAndSumOfTransactions1();
        n.setNbOfNtries(Long.toString(count));
        n.setSum(major(ccy, sum));
        return n;
    }

    private static ActiveOrHistoricCurrencyAndAmount amount(String ccy, long minor) {
        ActiveOrHistoricCurrencyAndAmount a = new ActiveOrHistoricCurrencyAndAmount();
        a.setCcy(ccy);
        a.setValue(major(ccy, minor));
        return a;
    }

    private static BigDecimal major(String ccy, long minor) {
        return CurrencyRules.toMajor(minor, ccy);
    }
}
