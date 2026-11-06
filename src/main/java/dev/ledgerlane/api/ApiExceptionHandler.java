package dev.ledgerlane.api;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps ledger rule violations to RFC 9457 problem+json. Framework errors are handled by Spring. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    ProblemDetail handle(LedgerException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle(e.title());
        return problem;
    }
}
