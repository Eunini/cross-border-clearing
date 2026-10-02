package io.github.eunini.clearing.gateway.engine;

/** The engine could not be reached or failed; the caller keeps its state and retries later. */
public class EngineUnavailableException extends RuntimeException {
    public EngineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
