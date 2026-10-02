package io.github.eunini.clearing.gateway.iso;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.UnmarshalException;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.ValidationEvent;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

/**
 * Parses and produces ISO 20022 messages using JAXB bindings generated from the
 * official XSDs (shipped in the iso20022-model module).
 *
 * <p>Inbound documents are validated against the XSD while unmarshalling, with
 * DTDs and external entities disabled (XXE-safe). Outbound documents are
 * validated too, so a coding error can never put a schema-invalid pacs.002 or
 * camt.053 on the wire.
 */
@Component
public class Iso20022Codec {

    public static final String PACS008_NS = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    public static final String PACS002_NS = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10";
    public static final String CAMT053_NS = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08";

    private final JAXBContext pacs008Context;
    private final JAXBContext pacs002Context;
    private final JAXBContext camt053Context;
    private final Schema pacs008Schema;
    private final Schema pacs002Schema;
    private final Schema camt053Schema;
    private final XMLInputFactory inputFactory;

    public Iso20022Codec() {
        try {
            pacs008Context = JAXBContext.newInstance(io.github.eunini.clearing.iso.pacs008.Document.class);
            pacs002Context = JAXBContext.newInstance(io.github.eunini.clearing.iso.pacs002.Document.class);
            camt053Context = JAXBContext.newInstance(io.github.eunini.clearing.iso.camt053.Document.class);
            pacs008Schema = loadSchema("pacs.008.001.08.xsd");
            pacs002Schema = loadSchema("pacs.002.001.10.xsd");
            camt053Schema = loadSchema("camt.053.001.08.xsd");
        } catch (JAXBException | SAXException e) {
            throw new IllegalStateException("Cannot initialise ISO 20022 bindings", e);
        }
        inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    private static Schema loadSchema(String file) throws SAXException {
        URL url = Iso20022Codec.class.getResource("/iso20022/xsd/" + file);
        if (url == null) {
            throw new IllegalStateException("Missing XSD on classpath: " + file);
        }
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newSchema(url);
    }

    /** Result of parsing an inbound pacs.008. Exactly one of document / errors is meaningful. */
    public record ParseResult<T>(T document, List<String> errors) {
        public boolean valid() {
            return document != null && errors.isEmpty();
        }
    }

    public ParseResult<io.github.eunini.clearing.iso.pacs008.Document> parsePacs008(byte[] xml) {
        List<String> errors = new ArrayList<>();
        try {
            Unmarshaller u = pacs008Context.createUnmarshaller();
            u.setSchema(pacs008Schema);
            u.setEventHandler(event -> {
                errors.add(describe(event));
                return errors.size() < 20; // keep going to report several problems, but bounded
            });
            XMLStreamReader reader = inputFactory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                Object root = u.unmarshal(reader);
                Object value = root instanceof JAXBElement<?> e ? e.getValue() : root;
                if (!(value instanceof io.github.eunini.clearing.iso.pacs008.Document doc)) {
                    errors.add("Root element is not a pacs.008.001.08 Document");
                    return new ParseResult<>(null, errors);
                }
                return new ParseResult<>(errors.isEmpty() ? doc : null, errors);
            } finally {
                reader.close();
            }
        } catch (UnmarshalException e) {
            if (errors.isEmpty()) {
                errors.add(rootCauseMessage(e));
            }
            return new ParseResult<>(null, errors);
        } catch (JAXBException | XMLStreamException e) {
            errors.add(rootCauseMessage(e));
            return new ParseResult<>(null, errors);
        }
    }

    public String writePacs002(io.github.eunini.clearing.iso.pacs002.Document doc) {
        return marshal(pacs002Context, pacs002Schema,
                new JAXBElement<>(new QName(PACS002_NS, "Document"),
                        io.github.eunini.clearing.iso.pacs002.Document.class, doc));
    }

    public String writeCamt053(io.github.eunini.clearing.iso.camt053.Document doc) {
        return marshal(camt053Context, camt053Schema,
                new JAXBElement<>(new QName(CAMT053_NS, "Document"),
                        io.github.eunini.clearing.iso.camt053.Document.class, doc));
    }

    public String writePacs008(io.github.eunini.clearing.iso.pacs008.Document doc) {
        return marshal(pacs008Context, pacs008Schema,
                new JAXBElement<>(new QName(PACS008_NS, "Document"),
                        io.github.eunini.clearing.iso.pacs008.Document.class, doc));
    }

    private static String marshal(JAXBContext ctx, Schema schema, Object element) {
        try {
            Marshaller m = ctx.createMarshaller();
            m.setSchema(schema);
            m.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
            StringWriter out = new StringWriter(1024);
            m.marshal(element, out);
            return out.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException("Generated ISO 20022 message is not schema-valid: " + rootCauseMessage(e), e);
        }
    }

    private static String describe(ValidationEvent event) {
        var loc = event.getLocator();
        String where = loc == null ? "" : " (line " + loc.getLineNumber() + ", col " + loc.getColumnNumber() + ")";
        return event.getMessage() + where;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
