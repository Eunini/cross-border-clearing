package io.github.eunini.clearing.gateway.web;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import io.github.eunini.clearing.gateway.payment.InboundMessageService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ISO 20022 entry point. Participants POST a pacs.008.001.08 document and
 * receive a pacs.002.001.10 status report synchronously. Business outcomes
 * (including rejections) are carried in the pacs.002 with HTTP 200; HTTP
 * errors are reserved for transport problems (oversized body, wrong media type).
 */
@RestController
public class Pacs008Controller {

    private final InboundMessageService messages;
    private final ClearingProperties props;

    public Pacs008Controller(InboundMessageService messages, ClearingProperties props) {
        this.messages = messages;
        this.props = props;
    }

    @PostMapping(path = "/iso20022/pacs.008",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> submit(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > props.maxMessageBytes()) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes((int) props.maxMessageBytes() + 1);
        }
        if (body.length > props.maxMessageBytes()) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        return ResponseEntity.ok(messages.handlePacs008(body));
    }
}
