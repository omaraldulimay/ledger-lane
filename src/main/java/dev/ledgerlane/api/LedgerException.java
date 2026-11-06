package dev.ledgerlane.api;

import org.springframework.http.HttpStatus;

/** A business rule violation that is reported to the client as problem+json. */
public class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public LedgerException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public static LedgerException invalid(String detail) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid transfer", detail);
    }

    public static LedgerException notFound(String detail) {
        return new LedgerException(HttpStatus.NOT_FOUND, "Not found", detail);
    }

    public static LedgerException conflict(String title, String detail) {
        return new LedgerException(HttpStatus.CONFLICT, title, detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
