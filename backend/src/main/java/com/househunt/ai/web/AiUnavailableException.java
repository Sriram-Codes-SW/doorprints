package com.househunt.ai.web;

/** The model provider failed (network, 4xx/5xx, quota exhausted, unparseable output). Mapped to HTTP 503. */
public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
