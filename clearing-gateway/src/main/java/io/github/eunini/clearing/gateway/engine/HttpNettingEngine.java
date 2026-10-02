package io.github.eunini.clearing.gateway.engine;

import io.github.eunini.clearing.gateway.config.ClearingProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP/JSON client for the Rust engine with bounded retries and exponential
 * backoff. 4xx responses mean the input itself is invalid and are not retried.
 */
@Component
public class HttpNettingEngine implements NettingEngine {

    private static final Logger log = LoggerFactory.getLogger(HttpNettingEngine.class);

    private final RestClient client;
    private final int maxAttempts;

    public HttpNettingEngine(RestClient.Builder builder, ClearingProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.engine().connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.engine().readTimeout());
        this.client = builder.baseUrl(props.engine().baseUrl()).requestFactory(factory).build();
        this.maxAttempts = Math.max(1, props.engine().maxAttempts());
    }

    @Override
    public EngineModels.NettingResult net(EngineModels.NettingRequest request) {
        return call("/v1/netting", request, EngineModels.NettingResult.class);
    }

    @Override
    public EngineModels.LsmResult resolve(EngineModels.LsmRequest request) {
        return call("/v1/lsm/resolve", request, EngineModels.LsmResult.class);
    }

    private <T> T call(String path, Object body, Class<T> type) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().body(type);
            } catch (HttpClientErrorException e) {
                throw new IllegalArgumentException("Engine rejected " + path + ": " + e.getResponseBodyAsString(), e);
            } catch (RestClientException e) {
                last = e;
                log.warn("Engine call {} failed (attempt {}/{}): {}", path, attempt, maxAttempts, e.getMessage());
                sleep(Duration.ofMillis(200L * (1L << (attempt - 1))));
            }
        }
        throw new EngineUnavailableException("Netting engine unavailable for " + path, last);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
