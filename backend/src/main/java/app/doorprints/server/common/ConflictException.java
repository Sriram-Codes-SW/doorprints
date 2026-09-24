package app.doorprints.server.common;

/** The request is valid but clashes with the current state (e.g. photo limit reached). Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
