package io.github.eunini.clearing.gateway.iso;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

public final class XmlDates {

    private static final DatatypeFactory FACTORY;

    static {
        try {
            FACTORY = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private XmlDates() {}

    /** ISODateTime in UTC with millisecond precision. */
    public static XMLGregorianCalendar dateTime(Instant instant) {
        ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
        XMLGregorianCalendar c = FACTORY.newXMLGregorianCalendar(GregorianCalendar.from(z));
        return c;
    }

    /** ISODate (no time, no zone). */
    public static XMLGregorianCalendar date(LocalDate d) {
        return FACTORY.newXMLGregorianCalendarDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth(),
                DatatypeConstants.FIELD_UNDEFINED);
    }
}
